# Samba fixture for culling sidecars (docs/testing/xmp-sidecars.md, ADR 0011).
#  - photos:    read only = no  (sidecars are written next to the photos)
#  - photos-ro: read only = yes (decisions stay in Hoard; the culling page shows the note)
# The photos are copied in after the container starts (`docker cp`): CC0 files from raw.pixls.us and
# synthetic JPEGs only.
FROM alpine:3.20
RUN apk add --no-cache samba
RUN adduser -D -H hoard && (echo hoardpass; echo hoardpass) | smbpasswd -a -s hoard \
 && mkdir -p /srv/photos /srv/photos-ro && chown -R hoard /srv/photos /srv/photos-ro
RUN printf '[global]\n server role = standalone server\n map to guest = never\n server min protocol = SMB2_02\n disable netbios = yes\n smb ports = 445\n log level = 1\n[photos]\n path = /srv/photos\n read only = no\n[photos-ro]\n path = /srv/photos-ro\n read only = yes\n' > /etc/samba/smb.conf
EXPOSE 445
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
