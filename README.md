# openapi

An OpenAPI documentation starter for Spring Boot. It renders the spec springdoc produces with **its own documentation UI**, and lifts information that already lives in your code into the docs.

Swagger UI is not used. The UI is a standalone bundle that only consumes `/v3/api-docs`, so it ships with zero runtime dependencies and every pixel is under your control.

## Install

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.doitdan:openapi:0.1.1")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.x")
}
```

Open `/docs` after startup. Auto-configuration kicks in whenever springdoc is on the classpath.

springdoc is a `compileOnly` dependency here, not a transitive one, because the springdoc version has to match your Spring Boot version — this library should not choose it for you. If your project already declares springdoc, the second line is redundant and one line is enough.

## The documentation UI

A three-column reference layout, the shape Stripe popularised and Scalar and Redoc follow.

```text
┌───────────┬─────────────────────────┬────────────────────┐
│ sidebar   │ prose                    │ code panel (sticky)│
│ search    │ description, parameters, │ cURL, samples,     │
│ endpoints │ request/response schema  │ responses, Try it  │
└───────────┴─────────────────────────┴────────────────────┘
```

- **Sidebar** — search (`/`), a command palette (`⌘K`), collapsible tag groups, and scroll-spy that tracks the endpoint you are reading
- **Prose** — markdown descriptions, a `Type · Name · Category · Description` parameter table, request and response schemas
- **Code panel** — sticky card with cURL, Kotlin, TypeScript and JavaScript samples, per-status response examples, and Try it out
- Dark-only Liquid Glass theme: translucent panels, ambient background, restrained motion

### Schemas read as JSON

Request and response bodies are shown as **annotated JSON** rather than a table, so deep nesting stays readable.

```jsonc
{
  "name"*: "string",                 // String
  "status"*: ["PENDING" ▾],          // click a value to choose it
  "profile"?: ▸{ … } 4 fields        // click the row to expand
}
```

- Nested objects and arrays start collapsed and summarise as `{ … } N fields`. Click a row to expand, or use `Expand all`
- `*` marks required, `?` marks optional — driven by Bean Validation (`@NotNull`, `@NotBlank`, …)
- **Enum values are pickable.** The picker lists values with their descriptions and adds a search box beyond eight values. Array enums allow multiple selection, and whatever you pick flows straight into the cURL, Kotlin and TypeScript samples

Every endpoint has a **Copy policy** button that copies the description, parameter table and request/response examples as one markdown blob — ready to paste into an AI assistant.

### Auth, headers and cookies

- **Auth** appears only when a scheme needs a value typed in (HTTP bearer/basic, OAuth2, apiKey in header or query)
- **Cookies** are managed separately. Cookie-based security schemes are pre-filled by name, and on the same origin the values are written to browser cookies so Try it out really sends them
- **Headers** lets you add custom headers that ride along with every request and every code sample

Server-side defaults:

```yaml
openapi:
  ui:
    headers:
      X-Tenant-Id: acme
    cookies:
      session: ""
```

## MCP server

The same documentation is served as an [MCP](https://modelcontextprotocol.io) server over Streamable HTTP at `{path}/mcp`, so an AI agent can read your API without scraping the page.

```json
{
  "mcpServers": {
    "orders-api-docs": { "type": "http", "url": "http://localhost:8080/docs/mcp" }
  }
}
```

Read-only tools:

| Tool | Answers |
| --- | --- |
| `list_endpoints` | Every endpoint with method, path, tag and summary. Filter by `tag` or `query` |
| `get_endpoint` | One endpoint in full: description and policy, parameters, request body, responses |
| `search_docs` | Free-text search across summaries and the markdown policy files |
| `get_schema` | One component schema by name, or the list of names |
| `get_typescript` | The generated `types` or `client` source, so the agent can write the file itself |

Because the policy markdown lands in the spec description, the agent gets your business rules, not just the shapes. The endpoint speaks JSON-RPC 2.0, answers `initialize`, `ping`, `tools/list` and `tools/call`, returns 202 for notifications, and validates the `Origin` header.

`get_endpoint` answers with everything one request needs — the server url, the auth scheme, every parameter and body field down the nested objects with its enum values and their meanings, a ready-to-send example, and the response shapes:

```
# POST /orders
server: https://api.example.com
auth: accessToken (cookie session)

## Request body (application/json)
### Fields
- status (enum, required)
  allowed: PENDING(Awaiting payment), PAID(Payment captured), CANCELLED(Cancelled by the buyer)
- shipping.address.postalCode (string, optional)
```

The MCP server is not a separate process and does not open a port of its own: it is a controller inside your service, on the same port as the API. Deploying it means routing `{path}/**` to the existing service — and remembering that it is as public as the rest of that host.

### Several services at once

Point the agent at one MCP server per service. The server names itself after `spring.application.name`, so the key in the agent's config file and the `serverInfo` it reports are unique per service without you writing anything. `api` is added when the name does not already carry it, because a bare service noun reads as a domain rather than an API — `orders-api` becomes `orders-api-docs`, and `orders` becomes `orders-api-docs` too. Override the whole name with `openapi.mcp.name`; with neither set it falls back to `openapi-docs`. The dialog behind **MCP server** in the sidebar shows the ready-made config for the service you are looking at.

Tool names repeat across servers, but MCP namespaces them by server, so `orders-api-docs` and `billing-api-docs` can be connected side by side.

## TypeScript export

The frontend can pull a typed interface straight from the running service — no code generator in the build pipeline.

| URL | Contents |
| --- | --- |
| `{path}/export/types.d.ts` | Every schema as an interface, enums as string unions, required vs optional |
| `{path}/export/client.ts` | A typed `fetch` client with one function per operation |
| `{path}/export/manifest.json` | The filenames for this service |

Filenames follow `spring.application.name`, so several services can be exported side by side — `orders-api.types.d.ts`, `billing-api.types.d.ts` — and the client imports the matching types module. The stem resolves in order: `openapi.export.name`, then `spring.application.name`, then the API title, then `api`.

```ts
import { createOrdersApiClient } from "./orders-api.client";

const api = createOrdersApiClient({ baseUrl: "https://api.example.com" });
const orders = await api.listOrders({ status: "PAID" });
```

### What the generator handles

| OpenAPI 3.1 shape | Emitted TypeScript |
| --- | --- |
| `"type": ["string", "null"]` | `field?: string` |
| `oneOf: [$ref, {"type": "null"}]` | `field?: Order` |
| `oneOf: [$refA, $refB]` | `A \| B` |
| `allOf: [$ref, {…}]` | `Base & { … }` |
| Nested DTO named `Outer.Inner` | `Outer_Inner` — a dot is not a legal identifier |

Everything the spec cannot describe stays `unknown` rather than being guessed at.

### Name collisions across services

Two services will have a `getCustomer` operation sooner or later. The client keeps every operation as a method on the object it returns, so the only module-level names it exports are prefixed with the service — `createOrdersApiClient`, `OrdersApiClientOptions`, `OrdersApiClient` — and two clients never collide.

Schema interfaces keep their own names, since a frontend that imports only one service should not have to read `OrdersApiCustomer`. Import the types module under a namespace when you use several:

```ts
import type * as OrdersApi from "./orders-api.types";
import type * as BillingApi from "./billing-api.types";

const buyer: OrdersApi.Customer = await ordersApi.getCustomer(id);
const payer: BillingApi.Customer = await billingApi.getCustomer(id);
```

## Spec enrichment

### Success status inference

The documented success code is derived from the HTTP method, so `@ApiResponses` is unnecessary.
Defaults: POST and PUT → 201, PATCH and DELETE → 204, GET → 200.

### Enum documentation

Enum descriptions are read from a member on the enum itself.

```kotlin
enum class OrderStatus(private val description: String) {
    PENDING("Awaiting payment"),
    PAID("Payment captured"),
}
```

The value picker shows value and description together, and the OpenAPI JSON carries both a human-readable markdown list (`- \`PENDING\`: Awaiting payment`) and a machine-readable `x-enum-descriptions` extension.

String fields annotated with a validation annotation that points at an enum (`@IsEnum` and friends) are supported too — register the annotation's FQCN and the `value` member is read for the allowed values.

### Markdown files as endpoint docs

If `resources/apidocs/{ControllerName}/{methodName}.md` exists, it becomes that operation's description. No annotation, just a file.

```text
src/main/resources/apidocs/
  OrderController/
    createOrder.md
```

Where those files live is configurable. `locations` takes an ordered list of resource patterns — the first match wins — and supports Spring resource globs (`classpath*:`, `**`).

| Placeholder | Resolves to | Example |
| --- | --- | --- |
| `{basePath}` | `openapi.markdown-docs.base-path` | `apidocs` |
| `{controller}` | Controller simple name | `OrderController` |
| `{method}` | Handler method name | `createOrder` |
| `{httpMethod}` | HTTP verb, lowercase | `post` |
| `{path}` | Full route, slugified | `orders-orderId` |
| `{operationId}` | OpenAPI operationId | `createOrder_1` |
| `{package}` | Controller package as a path | `com/example/api/order/controller` |
| `{parentPackage}` | One package up from the controller | `com/example/api/order` |

```yaml
openapi:
  markdown-docs:
    locations:
      - "classpath:{basePath}/{controller}/{method}.{httpMethod}.{path}.md"  # overload-safe
      - "classpath:{basePath}/{controller}/{method}.md"                      # plain fallback
      - "classpath*:**/docs/{controller}.{method}.md"                        # anywhere on the classpath
```

**Docs beside the code.** With controllers in `{feature}/controller`, `{parentPackage}` puts a `docs` package next to them so the policy lives with the feature it documents. Name the files by verb and route and one pattern covers everything — the pair is what OpenAPI itself uses to identify an operation, so it stays unique across overloads and across two controllers sharing a feature package:

```yaml
openapi:
  markdown-docs:
    locations:
      - "classpath:{parentPackage}/docs/{httpMethod}.{path}.md"
```

```text
application/order/
  controller/OrderController.kt       GET  /orders
  controller/OrderItemController.kt   GET  /orders/{orderId}/items
  docs/get.orders.md
  docs/get.orders-orderId-items.md
```

Markdown under a source directory only reaches the classpath if the build copies it, so add it to `processResources`:

```kotlin
tasks.processResources {
    from("src/main/kotlin") {
        include("**/docs/**/*.md")
    }
}
```

**Overloaded handlers.** When a controller has several handlers with the same method name — normal when you lean on polymorphism — the method name alone is ambiguous. Spring already requires those handlers to differ by route or verb, so put `{httpMethod}` and `{path}` in the pattern and each one gets its own file:

```text
apidocs/OrderController/
  createOrder.post.orders.md        # POST /orders
  createOrder.post.orders-bulk.md   # POST /orders/bulk
```

List the specific pattern first and the plain `{method}.md` after it, so overloads resolve precisely while everything else keeps the short name.

Put the knowledge that code cannot express here — business rules, policies, examples. It lands in the OpenAPI JSON `description`, so an AI reading only the spec still sees the policy.

Full markdown support: headings, nested lists, tables, task lists, block quotes, rules, and fenced code blocks with a language label, copy button and syntax highlighting.

### Type names

Types are shown as JVM data structures — `integer($int64)` → `Long`, `string($date-time)` → `LocalDateTime`, `array[string]` → `List<String>`, and `$ref` as the schema name.

## Configuration

```yaml
openapi:
  ui:
    enabled: true
    path: /docs
    docs-url: /v3/api-docs
    title: ""            # falls back to info.title from the spec
    try-it-out: true
    headers: {}
    cookies: {}
  success-status:
    enabled: true
    post: 201
    put: 201
    patch: 204
    delete: 204
  enum-descriptions:
    enabled: true
    description-member: description
    string-enum-annotations:
      - com.example.validation.IsEnum
  mcp:
    enabled: true
    name: ""             # falls back to {spring.application.name}-docs, then openapi-docs
    version: 1.0.0
    allowed-origins: []  # empty means same origin and localhost only
  export:
    enabled: true
    name: ""             # falls back to spring.application.name, then a slug of info.title
  markdown-docs:
    enabled: true
    base-path: apidocs
    locations:
      - "classpath:{basePath}/{controller}/{method}.{httpMethod}.{path}.md"
      - "classpath:{basePath}/{controller}/{method}.md"
```

## Requirements

- Java 17+
- Spring Boot 4.x (Spring MVC)
- springdoc-openapi 3.x
