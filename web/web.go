// Package web embeds the static UI files.
package web

import "embed"

//go:embed index.html style.css app.js
var FS embed.FS
