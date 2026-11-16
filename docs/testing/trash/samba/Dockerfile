FROM alpine:3.20
RUN apk add --no-cache samba
COPY files /seed
# media: a writable share (Shoot/ for culling and Files, a back-dated day folder another device left);
# rootro: its root is not writable by hoard, only Photos/ is (ADR 0023: "a read-only share root").
RUN adduser -D -H hoard && (echo hoardpass; echo hoardpass) | smbpasswd -a -s hoard \
 && mkdir -p /srv/media/Shoot /srv/media/Docs /srv/rootro/Photos \
 && cp /seed/DSC0000*.JPG /seed/DSC00001.xmp /srv/media/Shoot/ \
 && cp /seed/DSC00004.JPG /srv/rootro/Photos/ \
 && echo "report" > /srv/media/Docs/report.txt \
 && mkdir -p /srv/media/.hoard-trash/2026-10-01/Old \
 && cp /seed/DSC00005.JPG /srv/media/.hoard-trash/2026-10-01/Old/OLD00001.JPG \
 && chown -R hoard /srv/media /srv/rootro/Photos && chown root /srv/rootro && chmod 755 /srv/rootro
RUN printf '[global]\n server role = standalone server\n map to guest = never\n server min protocol = SMB2_02\n disable netbios = yes\n smb ports = 445\n log level = 1\n[media]\n path = /srv/media\n read only = no\n valid users = hoard\n[rootro]\n path = /srv/rootro\n read only = no\n valid users = hoard\n' > /etc/samba/smb.conf
EXPOSE 445
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
