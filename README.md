# HttpMan

A small Postman / Bruno like HTTP client written in plain Java (Swing + `java.net.http`), with **no dependencies**. Requires JDK 11+.

## Build & run

```bash
./buildJar.sh                 # -> target/httpman.jar (Maven if installed, javac otherwise)
java -jar target/httpman.jar  # or ./run.sh (builds if needed)
```
`mvn package` also works directly.

## Features

- **Top bar**: request name, HTTP verb (editable list), URL, **Send** (⌘/Ctrl+Enter, or Enter in the URL field; becomes *Cancel* while running) **Save** (⌘/Ctrl+S) and **Revert** (reload the last saved version).
- **Request tabs**
  - *Headers*: add / remove / enable-disable headers, quick "common header" list.
  - *Body*: body type **none**, **raw** (text/JSON/XML…, *Format JSON*, load from file), **x-www-form-urlencoded** (key/value table), **form-data** (multipart: text and file fields, optional per-field Content-Type) or **binary file**.
  - *Client certificate*: optional PKCS#12 (.p12/.pfx) file + password, *Check* button to verify the keystore.
  - *Settings*: request timeout, HTTP version, follow redirects, disable TLS verification, proxy (no proxy / system proxy / custom HTTP proxy with optional credentials).
  - *cURL*: export the request to a curl command, import a curl command (`-X -H -d --data-raw --data-binary --data-urlencode --json -F/--form --form-string -T -u -A -b -e -k -L -m -x -U --noproxy -E/--cert --pass -G -I --compressed --http1.1 --http2`…), paste & import from clipboard.
- **Left panel**: saved requests with a filter field (name, URL or method); New / Duplicate / Delete, right-click menu.
- **Response panel**: status code, time, size, and tabs *Body* (pretty JSON), *Headers*, *Raw request* (always filled, even when the request fails), *Raw response*, *Log* (settings used, redirects, error + stack trace).

## Storage

Saved requests are stored in `~/.httpman/requests.json` (override the folder with `-Dhttpman.home=/path`).
Note: certificate and proxy passwords are stored in plain text in that file.

## Limitations

- Headers `Content-Length`, `Connection`, `Upgrade`, `Expect` are managed by the Java HTTP client and can't be forced.
- SOCKS proxies are not supported.
