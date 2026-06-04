# hdd-healer image
# alpine:3.21 base + kubectl v1.33.1 binary baked in.
# Provides /bin/sh for the heal.sh script + kubectl for exec/patch/get.
# Platform: linux/arm64 (OrbStack k3s on Apple Silicon).
FROM alpine:3.21

ARG KUBECTL_VERSION=v1.33.1
ARG TARGETARCH=arm64

RUN apk add --no-cache ca-certificates \
    && wget -qO /usr/local/bin/kubectl \
       "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl" \
    && chmod +x /usr/local/bin/kubectl \
    && kubectl version --client

# The CronJob command is ["/bin/sh", "/scripts/heal.sh"], so no ENTRYPOINT is needed.
# ENTRYPOINT [] makes it explicit that the command array drives execution entirely.
ENTRYPOINT []
