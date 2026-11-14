// Not Go code: this file keeps server/web (the web gallery's sources and its node_modules, where some npm packages
// ship .go files) out of the hoard-server module, so `go vet ./...` and golangci-lint in server/ never walk it.
module github.com/henriquefps/hoard/server/web

go 1.27.3
