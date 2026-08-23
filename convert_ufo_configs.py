#!/usr/bin/env python3
"""Export V2Ray configurations stored in UFO's XML data store as share URLs."""

import base64
import json
import sys
import xml.etree.ElementTree as element_tree
from pathlib import Path
from urllib.parse import quote, urlencode


SOURCE_KEYS = {"ufo_app_area_servers_v5", "ufo_app_least_servers_v5"}


def encoded_query(parameters):
    """Encode only parameters with a meaningful value, preserving URI path slashes."""
    return urlencode(
        [(key, str(value)) for key, value in parameters.items() if value not in (None, "")],
        quote_via=quote,
        safe="/",
    )


def label(context, number):
    """Create a unique, human-readable fragment without relying on internal fields."""
    location = " ".join(
        str(context[key]) for key in ("transfers", "fellow") if context.get(key)
    )
    return f"{number:03d} {location or 'UFO'} {context.get('voice', '')}".strip()


def vless_url(config, context, number):
    server = config["settings"]["vnext"][0]
    user = server["users"][0]
    stream = config.get("streamSettings", {})
    security = stream.get("security", "none")
    parameters = {
        "encryption": user.get("encryption", "none"),
        "flow": user.get("flow"),
        "security": security,
        "type": stream.get("network", "tcp"),
    }

    if security == "reality":
        reality = stream.get("realitySettings", {})
        parameters.update(
            {
                "sni": reality.get("serverName"),
                "fp": reality.get("fingerprint"),
                "pbk": reality.get("publicKey"),
                "sid": reality.get("shortId"),
                "spx": reality.get("spiderX"),
            }
        )
    elif security == "tls":
        tls = stream.get("tlsSettings", {})
        alpn = tls.get("alpn", [])
        parameters.update(
            {
                "sni": tls.get("serverName"),
                "fp": tls.get("fingerprint"),
                "alpn": ",".join(alpn) if isinstance(alpn, list) else alpn,
            }
        )

    network = stream.get("network", "tcp")
    if network == "ws":
        websocket = stream.get("wsSettings", {})
        parameters.update(
            {
                "host": websocket.get("host") or websocket.get("headers", {}).get("Host"),
                "path": websocket.get("path"),
            }
        )
    elif network == "grpc":
        grpc = stream.get("grpcSettings", {})
        parameters["serviceName"] = grpc.get("serviceName")
        if grpc.get("multiMode"):
            parameters["mode"] = "multi"
    elif network == "xhttp":
        xhttp = stream.get("xhttpSettings", {})
        parameters.update(
            {
                "host": xhttp.get("host"),
                "path": xhttp.get("path"),
                "mode": xhttp.get("mode"),
                # This extension has no shorter standardized alias; retaining its
                # original Xray field name prevents losing the configuration value.
                "xPaddingBytes": xhttp.get("extra", {}).get("xPaddingBytes"),
            }
        )

    return (
        f"vless://{quote(user['id'], safe='')}@{server['address']}:{server['port']}?"
        f"{encoded_query(parameters)}#{quote(label(context, number), safe='')}"
    )


def shadowsocks_url(config, context, number):
    settings = config["settings"]
    settings = settings.get("servers", [settings])[0]
    credential = f"{settings['method']}:{settings['password']}"
    token = base64.urlsafe_b64encode(credential.encode()).decode().rstrip("=")
    query = "?uot=1" if settings.get("uot") else ""
    return (
        f"ss://{token}@{settings['address']}:{settings['port']}{query}"
        f"#{quote(label(context, number), safe='')}"
    )


def trojan_url(config, context, number):
    settings = config["settings"]
    server = settings.get("servers", [settings])[0]
    stream = config.get("streamSettings", {})
    tls = stream.get("tlsSettings", {})
    parameters = {
        "security": stream.get("security", "tls"),
        "type": stream.get("network", "tcp"),
        "sni": tls.get("serverName"),
        "fp": tls.get("fingerprint"),
    }
    return (
        f"trojan://{quote(server['password'], safe='')}@{server['address']}:{server['port']}?"
        f"{encoded_query(parameters)}#{quote(label(context, number), safe='')}"
    )


def vmess_url(config, context, number):
    server = config["settings"]["vnext"][0]
    user = server["users"][0]
    stream = config.get("streamSettings", {})
    websocket = stream.get("wsSettings", {})
    tls = stream.get("tlsSettings", {})
    payload = {
        "v": "2",
        "ps": label(context, number),
        "add": server["address"],
        "port": str(server["port"]),
        "id": user["id"],
        "aid": str(user.get("alterId", 0)),
        "scy": user.get("security", "auto"),
        "net": stream.get("network", "tcp"),
        "type": "none",
        "host": websocket.get("host") or websocket.get("headers", {}).get("Host", ""),
        "path": websocket.get("path", ""),
        "tls": stream.get("security", ""),
        "sni": tls.get("serverName", ""),
        "fp": tls.get("fingerprint", ""),
    }
    return "vmess://" + base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()


CONVERTERS = {
    "vless": vless_url,
    "shadowsocks": shadowsocks_url,
    "ss": shadowsocks_url,
    "trojan": trojan_url,
    "vmess": vmess_url,
}


def walk(value, context, output):
    if isinstance(value, dict):
        context = context | {
            key: value[key] for key in ("transfers", "hits", "fellow", "voice") if key in value
        }
        if isinstance(value.get("follow"), str) and isinstance(value.get("soon"), str):
            protocol = value["follow"].lower()
            if protocol not in CONVERTERS:
                raise ValueError(f"Unsupported protocol: {protocol}")
            output.append((protocol, json.loads(value["soon"]), context))
        for child in value.values():
            walk(child, context, output)
    elif isinstance(value, list):
        for child in value:
            walk(child, context, output)


def main():
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("ufo_app_data_store.xml")
    destination = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("v2ray_urls.txt")
    root = element_tree.parse(source).getroot()
    entries = []
    for node in root.findall("string"):
        if node.attrib.get("name") in SOURCE_KEYS:
            walk(json.loads(node.text), {}, entries)
    if not entries:
        raise ValueError("No UFO V2Ray configurations found")

    urls = [CONVERTERS[protocol](config, context, index) for index, (protocol, config, context) in enumerate(entries, 1)]
    destination.write_text("\n".join(urls) + "\n", encoding="utf-8")
    print(f"Wrote {len(urls)} URLs to {destination}")


if __name__ == "__main__":
    main()
