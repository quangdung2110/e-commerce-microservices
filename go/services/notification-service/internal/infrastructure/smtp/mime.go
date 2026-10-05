package smtp

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"mime/quotedprintable"
	"net/mail"
	"net/textproto"
	"sort"
	"strings"
	"time"

	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// reservedHeaders are set by the builder itself; a duplicate coming from
// Content.Headers is dropped rather than emitted twice.
var reservedHeaders = map[string]bool{
	"from": true, "to": true, "subject": true, "date": true, "message-id": true,
	"mime-version": true, "content-type": true, "content-transfer-encoding": true,
	strings.ToLower(constants.HeaderDeliveryID): true,
}

// BuildMIME renders the RFC 5322 message: multipart/alternative with a
// text/plain and a text/html part (12-render.md §5 produces both), UTF-8,
// quoted-printable or base64 depending on how much of the body is ASCII,
// RFC 2047 encoded Subject/From name, Date, Message-ID and every header the
// renderer attached (List-Unsubscribe on bulk) plus X-Notification-Delivery-Id.
func BuildMIME(msg *entity.EmailMessage, fromName, fromAddress, messageID string, now time.Time) ([]byte, error) {
	if msg.Content.Text == "" && msg.Content.HTML == "" {
		return nil, fmt.Errorf("smtp: message has neither text nor html body")
	}
	var buf bytes.Buffer
	writeHeader := func(name, value string) {
		buf.WriteString(name)
		buf.WriteString(": ")
		buf.WriteString(value)
		buf.WriteString("\r\n")
	}

	from := mail.Address{Name: fromName, Address: fromAddress}
	to := mail.Address{Address: msg.To.Raw()}
	writeHeader("From", from.String())
	writeHeader("To", to.String())
	writeHeader("Subject", encodeHeaderText(oneLine(msg.Content.Subject)))
	writeHeader("Date", now.Format(time.RFC1123Z))
	writeHeader("Message-ID", "<"+messageID+">")
	writeHeader(constants.HeaderDeliveryID, msg.DeliveryID.String())

	// Renderer headers in a stable order so the output is reproducible.
	names := make([]string, 0, len(msg.Content.Headers))
	for name := range msg.Content.Headers {
		if !reservedHeaders[strings.ToLower(name)] && validHeaderName(name) {
			names = append(names, name)
		}
	}
	sort.Strings(names)
	for _, name := range names {
		writeHeader(name, oneLine(msg.Content.Headers[name]))
	}

	writeHeader("MIME-Version", "1.0")
	mw := multipart.NewWriter(&buf)
	writeHeader("Content-Type", fmt.Sprintf("multipart/alternative; boundary=%q", mw.Boundary()))
	buf.WriteString("\r\n")

	// RFC 2046 §5.1.4: the plainest representation first, the richest last.
	if msg.Content.Text != "" {
		if err := writePart(mw, "text/plain", msg.Content.Text); err != nil {
			return nil, err
		}
	}
	if msg.Content.HTML != "" {
		if err := writePart(mw, "text/html", msg.Content.HTML); err != nil {
			return nil, err
		}
	}
	if err := mw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func writePart(mw *multipart.Writer, contentType, body string) error {
	hdr := textproto.MIMEHeader{}
	hdr.Set("Content-Type", contentType+"; charset=UTF-8")
	encoding := chooseEncoding(body)
	hdr.Set("Content-Transfer-Encoding", encoding)
	part, err := mw.CreatePart(hdr)
	if err != nil {
		return err
	}
	switch encoding {
	case "base64":
		return writeBase64(part, body)
	default:
		qp := quotedprintable.NewWriter(part)
		if _, err := io.WriteString(qp, body); err != nil {
			return err
		}
		return qp.Close()
	}
}

// chooseEncoding picks quoted-printable for mostly-ASCII bodies (readable in
// Mailpit's raw view, cheap) and base64 when more than a third of the bytes
// are non-ASCII (Vietnamese-heavy text would otherwise triple in size).
func chooseEncoding(body string) string {
	if body == "" {
		return "quoted-printable"
	}
	nonASCII := 0
	for i := 0; i < len(body); i++ {
		if body[i] >= 0x80 {
			nonASCII++
		}
	}
	if nonASCII*3 > len(body) {
		return "base64"
	}
	return "quoted-printable"
}

// writeBase64 emits base64 wrapped at 76 characters per RFC 2045 §6.8.
func writeBase64(w io.Writer, body string) error {
	encoded := base64.StdEncoding.EncodeToString([]byte(body))
	const lineLen = 76
	for len(encoded) > 0 {
		n := lineLen
		if len(encoded) < n {
			n = len(encoded)
		}
		if _, err := io.WriteString(w, encoded[:n]+"\r\n"); err != nil {
			return err
		}
		encoded = encoded[n:]
	}
	return nil
}

// encodeHeaderText applies RFC 2047 only when needed; ASCII stays readable.
func encodeHeaderText(s string) string {
	return mime.QEncoding.Encode("utf-8", s)
}

// oneLine strips CR/LF so a header value can never inject another header.
func oneLine(s string) string {
	return strings.TrimSpace(strings.NewReplacer("\r\n", " ", "\r", " ", "\n", " ").Replace(s))
}

// validHeaderName accepts RFC 5322 field names (printable ASCII except ':').
func validHeaderName(name string) bool {
	if name == "" {
		return false
	}
	for i := 0; i < len(name); i++ {
		c := name[i]
		if c < 33 || c > 126 || c == ':' {
			return false
		}
	}
	return true
}
