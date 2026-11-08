# daemon

`porelod`, the daemon an interface talks to over its socket; each area registers its methods here through its `<Area>Methods`. The contract is in porelo-wire, `../wire/src/main/resources/varlink/org.fuin.porelo.Tasks1/`, in the order its `parts` names. Built natively into `target/porelod`.
