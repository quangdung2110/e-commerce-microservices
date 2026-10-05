// Package migrations embeds the SQL migrations so the service (and notifctl)
// can run them without a copy of the files on disk (13-development.md §4).
package migrations

import "embed"

// FS holds every NNN_name.{up,down}.sql file in golang-migrate layout.
//
//go:embed *.sql
var FS embed.FS
