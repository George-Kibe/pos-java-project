# API collection

`pos-api.postman_collection.json` - every endpoint of the POS through the gateway (186 requests plus
a *Start here* folder), generated from the services' own OpenAPI specs. Postman and Insomnia both
import it.

## Use it

1. Import `pos-api.postman_collection.json`.
2. Import an environment:
   - `local.postman_environment.json` after `make demo-seed` - ids of real demo records filled in,
     signed in as a demo branch manager (`Demo-Password-2026`); or
   - `pos-api.postman_environment.json` - every variable, blank; set `email` and `password`.
3. Run **Start here > Sign in**. The token is stored in the collection and sent with every other
   request.

Ids in paths are variables: `{{productId}}`, `{{saleId}}`, `{{purchaseOrderId}}`... Change them in
the environment, or paste one from a previous response. Lane requests carry an `Idempotency-Key` of
`{{$guid}}`, fresh per call.

The demo branch manager cannot do everything (no user management, no head-office reports). Sign in
as the administrator from `.env` for those.

**Insomnia**: the sign-in script is Postman's; if your Insomnia version does not run it, copy
`accessToken` from the sign-in response into the collection's `accessToken` variable.

## Keep it current

```bash
make postman     # regenerate from the running services
make api-smoke   # send every GET to the stack; fails on any server error
```
