# Samba fixture for read-only detection (docs/testing/read-only.md).
#  - rw:        read only = no; rw/locked is root-owned 0555 (filesystem read-only subfolder),
#               rw/locked/note.txt is 0444; rw/open is writable.
#  - raiz:      read only = yes (like the owner's cachyos-fps share), files owned by the user.
#  - writelist: read only = yes, but `write list = hoard` makes it writable for this user.
#  - readlist:  read only = no, but `read list = hoard` makes it read-only for this user.
FROM alpine:3.20
RUN apk add --no-cache samba
RUN adduser -D -H hoard && (echo hoardpass; echo hoardpass) | smbpasswd -a -s hoard \
 && mkdir -p /srv/rw/locked /srv/rw/open /srv/raiz/Docs /srv/writelist /srv/readlist \
 && echo hi > /srv/rw/readme.txt && echo locked > /srv/rw/locked/note.txt \
 && echo hi > /srv/raiz/readme.txt && printf '# Notes\n' > /srv/raiz/Docs/notes.md \
 && echo hi > /srv/writelist/readme.txt && echo hi > /srv/readlist/readme.txt \
 && chown -R hoard /srv/rw /srv/raiz /srv/writelist /srv/readlist \
 && chown -R root:root /srv/rw/locked && chmod 0555 /srv/rw/locked && chmod 0444 /srv/rw/locked/note.txt
RUN printf '[global]\n server role = standalone server\n map to guest = never\n server min protocol = SMB2_02\n disable netbios = yes\n smb ports = 445\n log level = 1\n[rw]\n path = /srv/rw\n read only = no\n[raiz]\n path = /srv/raiz\n read only = yes\n[writelist]\n path = /srv/writelist\n read only = yes\n write list = hoard\n[readlist]\n path = /srv/readlist\n read only = no\n read list = hoard\n' > /etc/samba/smb.conf
EXPOSE 445
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
