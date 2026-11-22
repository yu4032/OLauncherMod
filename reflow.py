#!/usr/bin/env python3
"""按当前网格重排桌面图标坐标，保留文件夹 span 与子项关系。"""
import sqlite3
import sys

DB = sys.argv[1] if len(sys.argv) > 1 else "/workspace/launcher_edit.db"
COLS = int(sys.argv[2]) if len(sys.argv) > 2 else 8
ROWS = int(sys.argv[3]) if len(sys.argv) > 3 else 4

con = sqlite3.connect(DB)
cur = con.cursor()

rows = cur.execute(
    "select _id, screen, cellX, cellY, spanX, spanY from singledesktopitems "
    "where container=-100 order by screen, cellY, cellX, _id"
).fetchall()

occ = {}
moved = 0
for _id, screen, x, y, sx, sy in rows:
    sx = max(1, sx or 1)
    sy = max(1, sy or 1)
    nx = ny = 0
    placed = False
    while ny + sy <= ROWS:
        if nx + sx > COLS:
            nx = 0
            ny += 1
            continue
        free = True
        for dx in range(sx):
            for dy in range(sy):
                if (screen, nx + dx, ny + dy) in occ:
                    free = False
                    break
            if not free:
                break
        if free:
            placed = True
            break
        nx += 1
    if not placed:
        nx, ny = x, y
    for dx in range(sx):
        for dy in range(sy):
            occ[(screen, nx + dx, ny + dy)] = _id
    if (nx, ny) != (x, y):
        cur.execute("update singledesktopitems set cellX=?, cellY=?, vCellX=?, vCellY=? where _id=?", (nx, ny, nx, ny, _id))
        moved += 1

con.commit()
con.close()
print("reflow: %d items, %d moved, grid %dx%d" % (len(rows), moved, COLS, ROWS))
