docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t us-docker.pkg.dev/rational-aria-186218/lambdaplex-public/plex-dev-cn:0.1.3 \
  --push .

