# Samba 4.23 granting SMB3 directory leases, with Mac-like shares (spaces, accents, a home share).
# Reproduces smbj 0.15 serving one share's listing for another; see docs/testing/shares-macos.md.
FROM alpine:edge
RUN apk add --no-cache samba
RUN adduser -D -h /home/hoard hoard && (echo hoardpass; echo hoardpass) | smbpasswd -a -s hoard \
 && mkdir -p "/srv/Pasta Pública" /srv/storage/Shared /srv/root/Shared /home/hoard/Shared \
 && touch "/srv/Pasta Pública/only-public.txt" /srv/storage/only-storage.txt /srv/root/only-root.txt /home/hoard/only-home.txt /srv/storage/Shared/in-storage.txt /srv/root/Shared/in-root.txt /home/hoard/Shared/in-home.txt \
 && chown -R hoard /srv /home/hoard
RUN printf '[global]\n server role = standalone server\n map to guest = never\n server min protocol = SMB2_02\n disable netbios = yes\n smb ports = 445\n log level = 1\n smb3 directory leases = yes\n[homes]\n read only = yes\n browseable = no\n[Pasta Pública de Hoard]\n path = /srv/Pasta Pública\n[StorageExtension]\n path = /srv/storage\n[Macintosh HD]\n path = /srv/root\n' > /etc/samba/smb.conf
EXPOSE 445
CMD ["smbd", "--foreground", "--no-process-group", "--debug-stdout"]
