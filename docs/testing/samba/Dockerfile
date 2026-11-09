FROM alpine:3.20
RUN apk add --no-cache samba
RUN adduser -D -H hoard && (echo hoardpass; echo hoardpass) | smbpasswd -a -s hoard \
 && mkdir -p /srv/media/Photos /srv/private /srv/media/Empty \
 && head -c 7340033 /dev/urandom > /srv/media/Photos/big.bin \
 && echo hello > /srv/media/readme.txt && echo dot > /srv/media/.hidden \
 && chown -R hoard /srv/media /srv/private && chmod 700 /srv/private && chown root /srv/private
RUN printf '[global]\n server role = standalone server\n map to guest = never\n server min protocol = SMB2_02\n disable netbios = yes\n smb ports = 445\n log level = 1\n[media]\n path = /srv/media\n read only = no\n valid users = hoard\n comment = Media files\n[private]\n path = /srv/private\n read only = no\n[admin$]\n path = /srv/media\n valid users = hoard\n' > /etc/samba/smb.conf
EXPOSE 445
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
