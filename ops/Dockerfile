FROM postgres:18.6-alpine@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2

RUN apk add --no-cache gnupg

COPY --chmod=0755 ops/backup.sh /usr/local/bin/backup.sh
COPY --chmod=0755 ops/restore.sh /usr/local/bin/restore.sh

ENV GNUPGHOME=/tmp/gnupg

USER 70:70
ENTRYPOINT ["/usr/local/bin/backup.sh"]
