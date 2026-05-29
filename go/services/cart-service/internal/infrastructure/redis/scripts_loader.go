package redis

import _ "embed"

// Script sources are embedded in the scripts subpackage.
// This file re-exports them for use by adapters in the redis package.

// AddOrUpdateScript is the Lua script for atomic add-or-update of cart items.
//go:embed scripts/add_or_update.lua
var AddOrUpdateScript string

// BatchDeleteScript is the Lua script for batch deletion of cart items.
//go:embed scripts/batch_delete.lua
var BatchDeleteScript string

// RehydrateScript is the Lua script for silent recovery from PostgreSQL.
//go:embed scripts/rehydrate.lua
var RehydrateScript string
