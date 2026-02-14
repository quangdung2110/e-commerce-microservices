package utils

import "strings"

func MaskEmail(email string) string {
	parts := strings.Split(email, "@")
	if len(parts) != 2 {
		return email
	}
	local := parts[0]
	domain := parts[1]
	if len(local) <= 2 {
		return email
	}
	masked := string(local[0]) + "***" + string(local[len(local)-1])
	return masked + "@" + domain
}

func MaskPhone(phone string) string {
	if len(phone) < 8 {
		return phone
	}
	return phone[:4] + "***" + phone[len(phone)-4:]
}
