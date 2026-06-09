import re


def normalize(text: str) -> str:
    t = text.lower()
    for a, b in [("á","a"),("é","e"),("í","i"),("ó","o"),("ú","u"),("ñ","n")]:
        t = t.replace(a, b)
    t = t.replace("_", " ")
    return re.sub(r"[^\w\s]", " ", t)


def get_first_process_nodo(nodos: list[dict]) -> dict | None:
    skip = {"inicio", "fin"}
    for nodo in sorted(nodos, key=lambda n: n.get("order", 9999)):
        if (nodo.get("nodeType") or "").lower() not in skip:
            return nodo
    return None
