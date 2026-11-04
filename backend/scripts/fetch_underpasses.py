"""데모 지역의 지하차도를 OSM에서 찾아 출력한다.

    .venv/bin/python scripts/fetch_underpasses.py > /tmp/underpasses_osm.json

이름(name 또는 tunnel:name)에 "지하차도"나 "지하도로"가 들어간 도로를 이름별로 묶어 중심 좌표와 구를 낸다.
`base_risk`, `bypass_waypoint`는 사람이 정해 `shared/underpasses_gangnam.json`에 적는다.
"""

import json
import sys

import osmnx as ox

# 데모 지역 (left, bottom, right, top)
BBOX = (127.00, 37.47, 127.07, 37.53)


def main() -> None:
    roads = ox.features_from_bbox(BBOX, tags={"highway": True})
    roads = roads[roads.geometry.geom_type.isin(["LineString", "MultiLineString"])]
    name = roads.get("tunnel:name")
    roads = roads.assign(up_name=name.where(name.notna(), roads["name"]) if name is not None else roads["name"])
    roads = roads[roads["up_name"].fillna("").str.contains("지하차도|지하도로")]

    gus = ox.features_from_bbox(BBOX, tags={"boundary": "administrative", "admin_level": "7"})
    gus = gus[gus.geometry.geom_type.isin(["Polygon", "MultiPolygon"])]

    out = []
    for up_name, group in roads.groupby("up_name"):
        center = group.geometry.union_all().centroid
        gu = next((g for g, poly in zip(gus["name"], gus.geometry) if poly.contains(center)), None)
        osm_id = min(i for _, i in group.index)
        out.append({
            "id": f"osm-{osm_id}",
            "name": up_name,
            "lat": round(center.y, 6),
            "lng": round(center.x, 6),
            "gu": gu,
            "osm_ways": sorted(int(i) for _, i in group.index),
        })
    json.dump(sorted(out, key=lambda u: u["name"]), sys.stdout, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
