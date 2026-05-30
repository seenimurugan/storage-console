# Stage 1: build the Go binary
FROM golang:1.23-alpine AS builder

WORKDIR /src

COPY go.mod go.sum* ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" \
    -o /storage-console ./cmd/storage-console

# Stage 2: minimal runtime image
FROM alpine:3.20

ARG KUBECTL_VERSION=v1.33.0

# kubectl for in-cluster Job management; ca-certificates for HTTPS
RUN apk add --no-cache ca-certificates curl && \
    ARCH=$(arch | sed 's/aarch64/arm64/;s/x86_64/amd64/') && \
    curl -sSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${ARCH}/kubectl" \
         -o /usr/local/bin/kubectl && \
    chmod +x /usr/local/bin/kubectl

COPY --from=builder /storage-console /app/storage-console

EXPOSE 8080
ENTRYPOINT ["/app/storage-console"]
