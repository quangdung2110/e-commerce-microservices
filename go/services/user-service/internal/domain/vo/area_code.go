package vo

import "regexp"

// areaCodePattern is the VietnameseAdministrativeCode pattern of the OpenAPI
// contract. v1 validates the format only: cross-checking that a ward belongs to
// a district needs a master-data contract this service does not own.
var areaCodePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,20}$`)

// ValidAreaCode reports whether a province, district or ward code is well
// formed.
func ValidAreaCode(code string) bool { return areaCodePattern.MatchString(code) }
