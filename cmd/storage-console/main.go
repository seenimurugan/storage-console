package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"

	"github.com/nila/storage-console/internal/handlers"
	"github.com/nila/storage-console/web"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}

	// FileServer backed by the embedded FS.
	// Files live at the FS root: index.html, style.css, app.js
	fileServer := http.FileServer(http.FS(web.FS))

	mux := http.NewServeMux()

	// /static/<file> → strip prefix, serve from embedded FS
	mux.Handle("/static/", http.StripPrefix("/static/", fileServer))

	// Root → index.html (rewrite path so FileServer finds it)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		r2 := r.Clone(r.Context())
		r2.URL.Path = "/index.html"
		fileServer.ServeHTTP(w, r2)
	})

	// API routes
	mux.HandleFunc("/api/healthz", handlers.Healthz)

	mux.HandleFunc("/api/run/", func(w http.ResponseWriter, r *http.Request) {
		handlers.RunAction(w, r)
	})

	mux.HandleFunc("/api/jobs/", func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		switch {
		case strings.HasSuffix(path, "/status"):
			handlers.JobStatusHandler(w, r)
		case strings.HasSuffix(path, "/logs"):
			handlers.JobLogsHandler(w, r)
		default:
			http.NotFound(w, r)
		}
	})

	addr := fmt.Sprintf(":%s", port)
	log.Printf("storage-console listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
