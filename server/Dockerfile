# Poliswag Passport helper, for running next to Dragonite.
# Build from the server/ directory:  docker build -t poliswag-passport server
FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /poliswag-passport .
RUN mkdir -p /out/data

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /poliswag-passport /poliswag-passport
# Owned by the nonroot user (65532), so a fresh named volume mounted here is writable.
COPY --from=build --chown=65532:65532 /out/data /data
# In a container, workers (the Android app, remote browsers) connect over the network
# with WORKER_TOKEN. Loopback trust is off: a proxy in front would make everyone local.
ENV HOST=0.0.0.0 \
    PORT=5090 \
    LOCAL_WORKERS=false \
    POLISWAG_STATE_DIR=/data
VOLUME /data
EXPOSE 5090
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s CMD ["/poliswag-passport", "healthcheck"]
ENTRYPOINT ["/poliswag-passport"]
CMD ["serve"]
