# local-repo

A minimal Maven repository holding the 1.18.2 ports that this project depends
on but that are not published to any public Maven repository:

- `dev.su5ed.sinytra:fabric-loader:0.0.0+0.14.25+1.18.2`
- `dev.su5ed.sinytra.fabric-api:fabric-api:0.0.0-1.18.2`
- `org.sinytra.adapter:adapter:1.11.67-1.18.2`
- `org.sinytra.adapter:definition:1.11.67-1.18.2`
- `org.sinytra.adapter:runtime:1.0.0+1.18.2`
- `io.github.steelwoolmc:mixin-transmogrifier:0.4.7+1.18.2`

They are committed so that the GitHub Actions build resolves the same
dependencies as a developer machine, which relies on a populated
`~/.m2/repository` produced by building the sibling ports from source.
`build.gradle.kts` registers this directory as a Maven repository before
`mavenLocal()`, so a developer's local artifacts still win when present.
