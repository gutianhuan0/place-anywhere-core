package com.placeanywhere.core;

import org.joml.Vector3f;

/**
 * GJK（Gilbert-Johnson-Keerthi）+ EPA（Expanding Polytope Algorithm）碰撞检测。
 *
 * GJK：检测两个凸体是否相交。通过 Minkowski 差构建单纯形，如果单纯形包含原点则相交。
 * EPA：GJK 检测到相交后，从终止单纯形向外扩展多面体，找到离原点最近的面，
 *       该面的法线即为最小穿透方向，距离即为穿透深度。
 *
 * 用于自由方块 OBB 与实体 AABB 的碰撞检测：
 *   - OBB 的 support 函数：在旋转后的局部坐标系中找最远顶点
 *   - AABB 的 support 函数：沿方向符号取半长
 *   - Minkowski 差 = AABB - OBB
 *
 * 相比 SAT：
 *   - GJK 对任意凸体都精确，不限于 OBB vs AABB
 *   - EPA 返回的穿透向量是最优的（最小平移向量 MTV），不会出现"推向天空"问题
 *   - 支持任意旋转角度，无抖动
 */
public final class GJKEPA {

    private GJKEPA() {}

    /** 碰撞结果：null 表示无碰撞，否则返回最小穿透向量（从 OBB 指向 AABB，即推回方向）。 */
    public static Vector3f collide(BoxShape aabb, OBBShape obb) {
        // GJK 检测
        Vector3f[] simplex = new Vector3f[4];
        int simplexSize = gjkIntersect(aabb, obb, simplex);
        if (simplexSize == 0) return null; // 无碰撞

        // EPA 计算穿透向量
        return epa(aabb, obb, simplex, simplexSize);
    }

    // =========== GJK ===========

    /** GJK 检测：返回 0 表示无碰撞，>0 表示碰撞且 simplex 中有对应数量的顶点。 */
    private static int gjkIntersect(BoxShape a, OBBShape b, Vector3f[] simplexOut) {
        // 初始方向：从 B 指向 A（AABB 中心 - OBB 中心）
        Vector3f d = new Vector3f(a.cx - b.cx, a.cy - b.cy, a.cz - b.cz);
        if (d.lengthSquared() < 1e-12f) d.set(1, 0, 0);

        Vector3f s0 = support(a, b, d);
        simplexOut[0] = s0;
        int n = 1;

        d.set(0, 0, 0).sub(s0); // 朝原点方向

        for (int iter = 0; iter < 64; iter++) {
            Vector3f p = support(a, b, d);
            // 如果 p 在原点方向的投影小于 0，则 Minkowski 差不包含原点，无碰撞
            if (p.dot(d) < 0) return 0;

            simplexOut[n++] = p;
            // 检查单纯形是否包含原点，并更新方向 d
            int result = updateSimplex(simplexOut, n, d);
            if (result == 0) return n; // 包含原点，碰撞
            if (result < 0) return 0;  // 无碰撞
            n = result; // 返回新的单纯形大小
            if (n == 0) return 0;
        }
        return 0;
    }

    /** Minkowski 差的 support 函数：support(A-B, d) = support(A, d) - support(B, -d)。 */
    private static Vector3f support(BoxShape a, OBBShape b, Vector3f d) {
        Vector3f sa = a.support(d);
        Vector3f sb = b.support(d.negate(new Vector3f()));
        return sa.sub(sb);
    }

    /** 更新单纯形，返回：
     *   0 = 包含原点（碰撞）
     *  -1 = 无碰撞
     *  >0 = 新的单纯形大小 */
    private static int updateSimplex(Vector3f[] s, int n, Vector3f d) {
        if (n == 2) return updateLine(s, d);
        if (n == 3) return updateTriangle(s, d);
        if (n == 4) return updateTetrahedron(s, n, d);
        return -1;
    }

    private static int updateLine(Vector3f[] s, Vector3f d) {
        // s[1] 是最新加入的点，s[0] 是旧的
        Vector3f ab = new Vector3f(s[0]).sub(s[1]); // B->A
        Vector3f ao = new Vector3f(s[1]).negate();  // A->原点（注意最新点是 s[n-1]=s[1]）
        // 实际上最新点应该是最后加入的，但我们用数组顺序，s[0] 是第一个，s[n-1] 是最新
        // 重新：设最新点为 s[1]
        if (ab.dot(ao) > 0) {
            // 原点在 AB 方向，朝 AB x AO x AB 方向继续
            d.set(tripleProduct(ab, ao, ab));
            return 2;
        } else {
            // 原点在 A 的外侧，只保留 A
            s[0] = s[1];
            d.set(ao);
            return 1;
        }
    }

    private static int updateTriangle(Vector3f[] s, Vector3f d) {
        // 最新点是 s[2]，三角形 ABC
        Vector3f a = s[2], b = s[1], c = s[0];
        Vector3f ab = new Vector3f(b).sub(a);
        Vector3f ac = new Vector3f(c).sub(a);
        Vector3f ao = new Vector3f(a).negate();
        Vector3f abc = new Vector3f(ab).cross(ac); // 三角形法线

        if (new Vector3f(abc).cross(ac).dot(ao) > 0) {
            if (ac.dot(ao) > 0) {
                // 原点在 AC 外侧
                s[1] = s[0]; s[0] = a; // 保留 A, C -> 但顺序调整为 [C, A] 不对
                // 简化：保留 A 和 C
                s[0] = c; s[1] = a;
                d.set(tripleProduct(ac, ao, ac));
                return 2;
            } else {
                // 像 line case 一样处理 AB
                s[0] = b; s[1] = a;
                return updateLine(s, d);
            }
        } else if (new Vector3f(ab).cross(abc).dot(ao) > 0) {
            // 原点在 AB 外侧
            s[0] = b; s[1] = a;
            return updateLine(s, d);
        } else {
            // 原点在三角形平面内
            if (abc.dot(ao) > 0) {
                // 原点在正面
                d.set(abc);
                return 3;
            } else {
                // 原点在背面，翻转三角形
                Vector3f tmp = s[0];
                s[0] = s[1];
                s[1] = tmp;
                d.set(abc.negate());
                return 3;
            }
        }
    }

    private static int updateTetrahedron(Vector3f[] s, int n, Vector3f d) {
        // 最新点是 s[3]，四面体 ABCD
        Vector3f a = s[3], b = s[2], c = s[1], dd = s[0];
        Vector3f ab = new Vector3f(b).sub(a);
        Vector3f ac = new Vector3f(c).sub(a);
        Vector3f ad = new Vector3f(dd).sub(a);
        Vector3f ao = new Vector3f(a).negate();

        Vector3f abc = new Vector3f(ab).cross(ac);
        Vector3f acd = new Vector3f(ac).cross(ad);
        Vector3f adb = new Vector3f(ad).cross(ab);

        // 调整法线朝外（远离 D 的方向）
        if (abc.dot(ad) > 0) abc.negate();
        if (acd.dot(ab) > 0) acd.negate();
        if (adb.dot(ac) > 0) adb.negate();

        // BCD 面（对角顶点是 A）
        Vector3f bc = new Vector3f(c).sub(b);
        Vector3f bd = new Vector3f(dd).sub(b);
        Vector3f bcd = new Vector3f(bc).cross(bd);
        if (bcd.dot(new Vector3f(a).sub(b)) > 0) bcd.negate();

        if (abc.dot(ao) > 0) {
            // 原点在 ABC 面外
            s[0] = c; s[1] = b; s[2] = a;
            d.set(abc);
            return 3;
        }
        if (acd.dot(ao) > 0) {
            // 原点在 ACD 面外
            s[0] = dd; s[1] = c; s[2] = a;
            d.set(acd);
            return 3;
        }
        if (adb.dot(ao) > 0) {
            // 原点在 ADB 面外
            s[0] = b; s[1] = dd; s[2] = a;
            d.set(adb);
            return 3;
        }
        if (bcd.dot(ao) > 0) {
            // 原点在 BCD 面外，保留 BCD 三角形
            s[0] = dd; s[1] = c; s[2] = b;
            d.set(bcd);
            return 3;
        }
        // 原点在四面体内 -> 碰撞
        return 0;
    }

    /** 三重积 (A x B) x C */
    private static Vector3f tripleProduct(Vector3f a, Vector3f b, Vector3f c) {
        Vector3f ab = new Vector3f(a).cross(b);
        return ab.cross(c);
    }

    // =========== EPA ===========

    /** EPA 算法：从 GJK 的终止单纯形开始，扩展多面体找到离原点最近的面。 */
    private static Vector3f epa(BoxShape a, OBBShape b, Vector3f[] simplex, int n) {
        // 初始化多面体顶点列表
        java.util.List<Vector3f> vertices = new java.util.ArrayList<>();
        if (n >= 4) {
            // 四面体
            vertices.add(simplex[0]);
            vertices.add(simplex[1]);
            vertices.add(simplex[2]);
            vertices.add(simplex[3]);
        } else if (n == 3) {
            // 三角形：需要手动扩展成四面体
            // 找一个垂直于三角形法线的方向
            Vector3f ab = new Vector3f(simplex[1]).sub(simplex[0]);
            Vector3f ac = new Vector3f(simplex[2]).sub(simplex[0]);
            Vector3f normal = new Vector3f(ab).cross(ac).normalize();
            vertices.add(simplex[0]);
            vertices.add(simplex[1]);
            vertices.add(simplex[2]);
            Vector3f p = support(a, b, normal);
            vertices.add(p);
        } else {
            // 退化情况，返回一个默认推回方向（向上）
            return new Vector3f(0, 0.1f, 0);
        }

        // 面列表：每个面是 3 个顶点索引
        java.util.List<int[]> faces = new java.util.ArrayList<>();
        faces.add(new int[]{0, 1, 2});
        faces.add(new int[]{0, 3, 1});
        faces.add(new int[]{0, 2, 3});
        faces.add(new int[]{1, 3, 2});

        Vector3f bestNormal = new Vector3f(0, 1, 0);
        float bestDist = Float.MAX_VALUE;

        for (int iter = 0; iter < 64; iter++) {
            // 找离原点最近的面
            float minDist = Float.MAX_VALUE;
            int bestFace = -1;
            Vector3f bestFaceNormal = null;

            for (int i = 0; i < faces.size(); i++) {
                int[] f = faces.get(i);
                Vector3f v0 = vertices.get(f[0]);
                Vector3f v1 = vertices.get(f[1]);
                Vector3f v2 = vertices.get(f[2]);

                Vector3f e1 = new Vector3f(v1).sub(v0);
                Vector3f e2 = new Vector3f(v2).sub(v0);
                Vector3f normal = new Vector3f(e1).cross(e2);
                float len2 = normal.lengthSquared();
                if (len2 < 1e-12f) continue;
                normal.div((float) Math.sqrt(len2)); // normalize

                float dist = v0.dot(normal);
                if (dist < 0) {
                    dist = -dist;
                    normal.negate();
                }

                if (dist < minDist) {
                    minDist = dist;
                    bestFace = i;
                    bestFaceNormal = normal;
                }
            }

            if (bestFace == -1) break;

            // 沿最近面的法线方向找 support 点
            Vector3f p = support(a, b, bestFaceNormal);
            float d = p.dot(bestFaceNormal);

            if (d - minDist < 1e-6f) {
                // 收敛，返回穿透向量
                bestNormal = bestFaceNormal;
                bestDist = minDist;
                break;
            }

            // 添加新顶点
            int newIdx = vertices.size();
            vertices.add(p);

            // 移除能被新顶点"看到"的面，并收集边界边
            java.util.List<int[]> boundaryEdges = new java.util.ArrayList<>();
            java.util.List<int[]> newFaces = new java.util.ArrayList<>();

            for (int i = 0; i < faces.size(); i++) {
                int[] f = faces.get(i);
                Vector3f v0 = vertices.get(f[0]);
                Vector3f v1 = vertices.get(f[1]);
                Vector3f v2 = vertices.get(f[2]);

                Vector3f e1 = new Vector3f(v1).sub(v0);
                Vector3f e2 = new Vector3f(v2).sub(v0);
                Vector3f normal = new Vector3f(e1).cross(e2);

                // 如果新顶点在面的正面（能看到），移除此面
                Vector3f toNew = new Vector3f(p).sub(v0);
                if (normal.dot(toNew) > 0) {
                    // 收集边界边
                    addBoundaryEdge(boundaryEdges, f[0], f[1]);
                    addBoundaryEdge(boundaryEdges, f[1], f[2]);
                    addBoundaryEdge(boundaryEdges, f[2], f[0]);
                } else {
                    newFaces.add(f);
                }
            }

            // 用边界边和新顶点创建新面
            for (int[] edge : boundaryEdges) {
                newFaces.add(new int[]{edge[0], edge[1], newIdx});
            }

            faces = newFaces;
        }

        // 穿透向量 = 法线 * 深度
        return new Vector3f(bestNormal).mul(bestDist);
    }

    /** 添加边界边：如果反向边已存在则移除，否则添加。 */
    private static void addBoundaryEdge(java.util.List<int[]> edges, int a, int b) {
        for (int i = 0; i < edges.size(); i++) {
            int[] e = edges.get(i);
            if (e[0] == b && e[1] == a) {
                edges.remove(i);
                return;
            }
        }
        edges.add(new int[]{a, b});
    }

    // =========== 形状定义 ===========

    /** AABB 形状（实体碰撞箱）。 */
    public static class BoxShape {
        public final float cx, cy, cz;
        public final float hx, hy, hz;

        public BoxShape(float cx, float cy, float cz, float hx, float hy, float hz) {
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.hx = hx; this.hy = hy; this.hz = hz;
        }

        /** support(d) = 中心 + 各轴符号 * 半长。 */
        public Vector3f support(Vector3f d) {
            return new Vector3f(
                cx + (d.x >= 0 ? hx : -hx),
                cy + (d.y >= 0 ? hy : -hy),
                cz + (d.z >= 0 ? hz : -hz)
            );
        }
    }

    /** OBB 形状（旋转的方块碰撞箱）。 */
    public static class OBBShape {
        public final float cx, cy, cz;
        public final float hx, hy, hz;
        public final Vector3f axisX, axisY, axisZ;

        public OBBShape(float cx, float cy, float cz, float hx, float hy, float hz,
                        Vector3f axisX, Vector3f axisY, Vector3f axisZ) {
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.axisX = axisX;
            this.axisY = axisY;
            this.axisZ = axisZ;
        }

        /** support(d) = 中心 + Σ 符号(half_i * (axis_i · d)) * half_i * axis_i。 */
        public Vector3f support(Vector3f d) {
            float dx = axisX.x * d.x + axisX.y * d.y + axisX.z * d.z;
            float dy = axisY.x * d.x + axisY.y * d.y + axisY.z * d.z;
            float dz = axisZ.x * d.x + axisZ.y * d.y + axisZ.z * d.z;

            float sx = dx >= 0 ? hx : -hx;
            float sy = dy >= 0 ? hy : -hy;
            float sz = dz >= 0 ? hz : -hz;

            return new Vector3f(
                cx + axisX.x * sx + axisY.x * sy + axisZ.x * sz,
                cy + axisX.y * sx + axisY.y * sy + axisZ.y * sz,
                cz + axisX.z * sx + axisY.z * sy + axisZ.z * sz
            );
        }
    }
}
