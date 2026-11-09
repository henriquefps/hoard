FROM alpine:3.20
RUN apk add --no-cache samba
RUN adduser -D -H hoard && (echo hoardpass; echo hoardpass) | smbpasswd -a -s hoard \
 && mkdir -p /srv/media/Photos /srv/private /srv/media/Empty \
 && head -c 7340033 /dev/urandom > /srv/media/Photos/big.bin \
 && echo hello > /srv/media/readme.txt && echo dot > /srv/media/.hidden \
 && mkdir -p /srv/media/Docs \
 && printf '# Notes\n\n- [x] Samba\n- [ ] Edit me in Hoard\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nSee [crlf](crlf.txt).\n' > /srv/media/Docs/notes.md \
 && printf 'line one\r\nline two\r\n' > /srv/media/Docs/crlf.txt \
 && printf 'Relat\363rio de manuten\347\343o \200 10\n' > /srv/media/Docs/latin1.txt \
 && head -c 6000000 /dev/zero | tr '\0' 'x' > /srv/media/Docs/big.log \
 && chown -R hoard /srv/media /srv/private && chmod 700 /srv/private && chown root /srv/private
RUN printf '[global]\n server role = standalone server\n map to guest = never\n server min protocol = SMB2_02\n disable netbios = yes\n smb ports = 445\n log level = 1\n[media]\n path = /srv/media\n read only = no\n valid users = hoard\n comment = Media files\n[private]\n path = /srv/private\n read only = no\n[admin$]\n path = /srv/media\n valid users = hoard\n' > /etc/samba/smb.conf
EXPOSE 445
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
