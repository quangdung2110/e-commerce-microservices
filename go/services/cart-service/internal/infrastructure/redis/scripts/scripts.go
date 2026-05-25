package scripts

import _ "embed"

//go:embed add_or_update.lua
var AddOrUpdateScript string

//go:embed batch_delete.lua
var BatchDeleteScript string

//go:embed rehydrate.lua
var RehydrateScript string
