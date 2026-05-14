#!/usr/bin/env python3
import tarfile
path = 'android/app/src/main/assets/payload-v2-with-npm.tar.xz'
with tarfile.open(path, 'r:xz') as tf:
    names = [m.name for m in tf.getmembers() if 'lib/node_modules/npm/bin/npm-cli.js' in m.name]
print('npm-cli entries:', len(names))
for name in names[:10]:
    print(name)
