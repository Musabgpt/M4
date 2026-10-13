"""Bounded public web reads with DNS pinning and explicit loopback scopes."""
import gzip
import http.client
from html.parser import HTMLParser
import ipaddress
import socket
import ssl
from urllib.parse import urlsplit, urljoin, parse_qs, urlencode
import xml.etree.ElementTree as ET

class TextReader(HTMLParser):
    def __init__(self): super().__init__(); self.text, self.links, self.skip, self.current = [], [], 0, None
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in {"script", "style", "noscript"}: self.skip += 1
        if tag == "a" and attrs.get("href"): self.current = {"url": attrs["href"], "title": ""}
    def handle_endtag(self, tag):
        if tag in {"script", "style", "noscript"}: self.skip = max(0, self.skip - 1)
        if tag == "a" and self.current: self.links.append(self.current); self.current = None
    def handle_data(self, data):
        if not self.skip:
            if len(self.text) < 10000: self.text.append(data.strip())
            if self.current: self.current["title"] += data[:300]

class WebClient:
    def __init__(self, local_origins=()): self.local_origins = set(local_origins)
    def target(self, raw):
        url = urlsplit(raw)
        if url.scheme not in {"https", "http"} or not url.hostname or url.username or url.password: raise ValueError("Web URL must be HTTP(S) without embedded credentials")
        port = url.port or (443 if url.scheme == "https" else 80)
        origin = (url.scheme, url.hostname.lower(), port)
        addresses = sorted({row[4][0] for row in socket.getaddrinfo(url.hostname, port, type=socket.SOCK_STREAM)})
        if not addresses or any(not ipaddress.ip_address(a).is_global for a in addresses):
            if origin not in self.local_origins or any(not ipaddress.ip_address(a).is_loopback for a in addresses): raise ValueError("Private, metadata and loopback addresses require an explicit host scope")
        if url.scheme != "https" and origin not in self.local_origins: raise ValueError("Public web reads require HTTPS")
        return url, port, addresses[0]
    def fetch(self, raw):
        for _ in range(4):
            url, port, address = self.target(raw)
            connection = http.client.HTTPConnection(url.hostname, port, timeout=20)
            connection.sock = socket.create_connection((address, port), timeout=20)
            if url.scheme == "https": connection.sock = ssl.create_default_context().wrap_socket(connection.sock, server_hostname=url.hostname)
            try:
                connection.request("GET", (url.path or "/") + ("?" + url.query if url.query else ""), headers={"Host": url.netloc, "User-Agent": "M4/2.0 public-web-reader", "Accept": "text/html,application/json,text/plain,application/rss+xml", "Accept-Encoding": "identity"})
                response = connection.getresponse()
                if response.status in {301, 302, 303, 307, 308}:
                    raw = urljoin(raw, response.getheader("Location", "")); continue
                body = response.read(1000001)
                if len(body) > 1000000: raise ValueError("Web response exceeds 1MB")
                if response.getheader("Content-Encoding", "identity") != "identity": raise ValueError("Unexpected compressed web response")
                if response.status >= 400: raise ValueError("Web HTTP " + str(response.status))
                mime = response.getheader("Content-Type", "text/plain").lower()
                if not any(t in mime for t in ("text/", "json", "xml")): raise ValueError("Binary response is not a WebFetch document")
                text = body.decode("utf-8", "replace"); links = []
                if "html" in mime:
                    parser = TextReader(); parser.feed(text); text = "\n".join(t for t in parser.text if t); links = [{"title": x["title"].strip(), "url": urljoin(raw, x["url"])} for x in parser.links[:100]]
                return {"url": raw, "status_code": response.status, "content_type": mime, "text": text[:30000], "links": links, "untrusted": True}
            finally: connection.close()
        raise ValueError("Web redirect limit exceeded")
    def search(self, query):
        if not query.strip() or len(query) > 500: raise ValueError("Invalid search query")
        errors = []
        for provider, url in (("DuckDuckGo", "https://html.duckduckgo.com/html/?" + urlencode({"q": query})), ("Bing RSS", "https://www.bing.com/search?" + urlencode({"q": query, "format": "rss"}))):
            try:
                page = self.fetch(url); results = []
                if provider == "Bing RSS":
                    xml = ET.fromstring(page["text"])
                    results = [{"title": n.findtext("title", ""), "url": n.findtext("link", ""), "snippet": n.findtext("description", "")[:500]} for n in xml.findall("./channel/item")[:10]]
                else:
                    for item in page["links"]:
                        link = item["url"]
                        if "duckduckgo.com/l/" in link: link = parse_qs(urlsplit(link).query).get("uddg", [link])[0]
                        if item["title"] and urlsplit(link).scheme == "https" and "duckduckgo.com" not in urlsplit(link).hostname: results.append(dict(item, url=link))
                        if len(results) >= 10: break
                if not results: raise ValueError("Search service returned no usable results")
                return {"provider": provider, "results": results, "untrusted": True}
            except (OSError, ValueError, ET.ParseError) as error: errors.append(provider + ": " + str(error)[:120])
        raise ValueError("Search unavailable: " + "; ".join(errors))
