package main

import (
	"bytes"
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

//go:embed static
var staticFS embed.FS

type sendRequest struct {
	Method  string            `json:"method"`
	URL     string            `json:"url"`
	Headers map[string]string `json:"headers"`
	Body    string            `json:"body"`
}

type sendResponse struct {
	Status     int               `json:"status"`
	StatusText string            `json:"status_text"`
	Headers    map[string]string `json:"headers"`
	Body       string            `json:"body"`
	DurationMs int64             `json:"duration_ms"`
	Error      string            `json:"error,omitempty"`
}

func main() {
	staticSub, err := fs.Sub(staticFS, "static")
	if err != nil {
		log.Fatal(err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/send", handleSend)
	mux.Handle("/", http.FileServer(http.FS(staticSub)))

	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	log.Printf("API tester running at http://localhost:%s", port)
	log.Printf("Press Ctrl+C to stop")
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatal(err)
	}
}

func handleSend(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req sendRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, sendResponse{Error: fmt.Sprintf("parse request: %v", err)})
		return
	}
	req.Method = strings.ToUpper(strings.TrimSpace(req.Method))
	if req.Method == "" {
		req.Method = http.MethodGet
	}
	if req.URL == "" {
		writeJSON(w, http.StatusBadRequest, sendResponse{Error: "url is required"})
		return
	}

	var bodyReader io.Reader
	if req.Body != "" && req.Method != http.MethodGet && req.Method != http.MethodHead {
		bodyReader = strings.NewReader(req.Body)
	}

	target, err := url.Parse(req.URL)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, sendResponse{Error: fmt.Sprintf("parse url: %v", err)})
		return
	}
	if target.RawQuery != "" {
		target.RawQuery = target.Query().Encode()
	}

	outReq, err := http.NewRequestWithContext(context.Background(), req.Method, target.String(), bodyReader)
	if err != nil {
		writeJSON(w, http.StatusBadRequest, sendResponse{Error: fmt.Sprintf("build request: %v", err)})
		return
	}

	for k, v := range req.Headers {
		if strings.TrimSpace(k) == "" {
			continue
		}
		outReq.Header.Set(k, v)
	}
	if outReq.Header.Get("User-Agent") == "" {
		outReq.Header.Set("User-Agent", "api-tester/1.0")
	}

	client := &http.Client{Timeout: 30 * time.Second}
	start := time.Now()
	resp, err := client.Do(outReq)
	durationMs := time.Since(start).Milliseconds()
	if err != nil {
		writeJSON(w, http.StatusBadGateway, sendResponse{Error: fmt.Sprintf("send request: %v", err), DurationMs: durationMs})
		return
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20)) // max 2MB
	if err != nil {
		writeJSON(w, http.StatusBadGateway, sendResponse{Error: fmt.Sprintf("read response: %v", err), DurationMs: durationMs})
		return
	}

	headers := make(map[string]string, len(resp.Header))
	for k, v := range resp.Header {
		headers[k] = strings.Join(v, ", ")
	}

	res := sendResponse{
		Status:     resp.StatusCode,
		StatusText: resp.Status,
		Headers:    headers,
		Body:       string(respBody),
		DurationMs: durationMs,
	}
	writeJSON(w, http.StatusOK, res)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func formatJSON(s string) string {
	var buf bytes.Buffer
	if err := json.Indent(&buf, []byte(s), "", "  "); err != nil {
		return s
	}
	return buf.String()
}
