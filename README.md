# jenkins-atom-node

Node.js — port of **nodejs** (59,707) as an atom.

Exports `NODE_HOME` and a `PATH` with the node directory in front: the next step sees `$node` without any
installer. This is the atom that proves raw environment export (runner 0.4.0) works.

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
