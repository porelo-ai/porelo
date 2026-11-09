# api

The Apache-2.0 contract an external planning adapter implements, published as `org.fuin.porelo:porelo-planning-api`. It contains `PlanningAdapter`, `PlanningMain`, the `org.fuin.porelo.Planning1` wire definition, the client and discovery; its only Porelo dependency is `porelo-wire`.

The adapter receives settings and a token with each data call and inherits the caller's environment. See [writing a planning adapter](../../doc/planning-adapters.md) and [the license](LICENSE).
