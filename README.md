# jenkins-atom-node

Node.js — port of **nodejs** (59,707) as an atom.

Exports `NODE_HOME` and a `PATH` with the node directory in front: the next step sees `$node` without any
installer. This is the atom that proves raw environment export (runner 0.4.0) works.

## Build and test

```bash
mvn verify
```

`mvn verify` packages the jar and then runs the contract tests through failsafe — the packaged jar started the way
the runner starts it (`input.json` + the `atom_*` environment).
