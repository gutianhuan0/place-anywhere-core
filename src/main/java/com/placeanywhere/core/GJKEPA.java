package com.placeanywhere.core;

import org.joml.Vector3f;


















public final class GJKEPA {

    private GJKEPA() {}


    public static Vector3f collide(BoxShape aabb, OBBShape obb) {

        Vector3f[] simplex = new Vector3f[4];
        int simplexSize = gjkIntersect(aabb, obb, simplex);
        if (simplexSize == 0) return null;


        return epa(aabb, obb, simplex, simplexSize);
    }




    private static int gjkIntersect(BoxShape a, OBBShape b, Vector3f[] simplexOut) {

        Vector3f d = new Vector3f(a.cx - b.cx, a.cy - b.cy, a.cz - b.cz);
        if (d.lengthSquared() < 1e-12f) d.set(1, 0, 0);

        Vector3f s0 = support(a, b, d);
        simplexOut[0] = s0;
        int n = 1;

        d.set(0, 0, 0).sub(s0);

        for (int iter = 0; iter < 64; iter++) {
            Vector3f p = support(a, b, d);

            if (p.dot(d) < 0) return 0;

            simplexOut[n++] = p;

            int result = updateSimplex(simplexOut, n, d);
            if (result == 0) return n;
            if (result < 0) return 0;
            n = result;
            if (n == 0) return 0;
        }
        return 0;
    }


    private static Vector3f support(BoxShape a, OBBShape b, Vector3f d) {
        Vector3f sa = a.support(d);
        Vector3f sb = b.support(d.negate(new Vector3f()));
        return sa.sub(sb);
    }





    private static int updateSimplex(Vector3f[] s, int n, Vector3f d) {
        if (n == 2) return updateLine(s, d);
        if (n == 3) return updateTriangle(s, d);
        if (n == 4) return updateTetrahedron(s, n, d);
        return -1;
    }

    private static int updateLine(Vector3f[] s, Vector3f d) {

        Vector3f ab = new Vector3f(s[0]).sub(s[1]);
        Vector3f ao = new Vector3f(s[1]).negate();


        if (ab.dot(ao) > 0) {

            d.set(tripleProduct(ab, ao, ab));
            return 2;
        } else {

            s[0] = s[1];
            d.set(ao);
            return 1;
        }
    }

    private static int updateTriangle(Vector3f[] s, Vector3f d) {

        Vector3f a = s[2], b = s[1], c = s[0];
        Vector3f ab = new Vector3f(b).sub(a);
        Vector3f ac = new Vector3f(c).sub(a);
        Vector3f ao = new Vector3f(a).negate();
        Vector3f abc = new Vector3f(ab).cross(ac);

        if (new Vector3f(abc).cross(ac).dot(ao) > 0) {
            if (ac.dot(ao) > 0) {

                s[1] = s[0]; s[0] = a;

                s[0] = c; s[1] = a;
                d.set(tripleProduct(ac, ao, ac));
                return 2;
            } else {

                s[0] = b; s[1] = a;
                return updateLine(s, d);
            }
        } else if (new Vector3f(ab).cross(abc).dot(ao) > 0) {

            s[0] = b; s[1] = a;
            return updateLine(s, d);
        } else {

            if (abc.dot(ao) > 0) {

                d.set(abc);
                return 3;
            } else {

                Vector3f tmp = s[0];
                s[0] = s[1];
                s[1] = tmp;
                d.set(abc.negate());
                return 3;
            }
        }
    }

    private static int updateTetrahedron(Vector3f[] s, int n, Vector3f d) {

        Vector3f a = s[3], b = s[2], c = s[1], dd = s[0];
        Vector3f ab = new Vector3f(b).sub(a);
        Vector3f ac = new Vector3f(c).sub(a);
        Vector3f ad = new Vector3f(dd).sub(a);
        Vector3f ao = new Vector3f(a).negate();

        Vector3f abc = new Vector3f(ab).cross(ac);
        Vector3f acd = new Vector3f(ac).cross(ad);
        Vector3f adb = new Vector3f(ad).cross(ab);


        if (abc.dot(ad) > 0) abc.negate();
        if (acd.dot(ab) > 0) acd.negate();
        if (adb.dot(ac) > 0) adb.negate();


        Vector3f bc = new Vector3f(c).sub(b);
        Vector3f bd = new Vector3f(dd).sub(b);
        Vector3f bcd = new Vector3f(bc).cross(bd);
        if (bcd.dot(new Vector3f(a).sub(b)) > 0) bcd.negate();

        if (abc.dot(ao) > 0) {

            s[0] = c; s[1] = b; s[2] = a;
            d.set(abc);
            return 3;
        }
        if (acd.dot(ao) > 0) {

            s[0] = dd; s[1] = c; s[2] = a;
            d.set(acd);
            return 3;
        }
        if (adb.dot(ao) > 0) {

            s[0] = b; s[1] = dd; s[2] = a;
            d.set(adb);
            return 3;
        }
        if (bcd.dot(ao) > 0) {

            s[0] = dd; s[1] = c; s[2] = b;
            d.set(bcd);
            return 3;
        }

        return 0;
    }


    private static Vector3f tripleProduct(Vector3f a, Vector3f b, Vector3f c) {
        Vector3f ab = new Vector3f(a).cross(b);
        return ab.cross(c);
    }




    private static Vector3f epa(BoxShape a, OBBShape b, Vector3f[] simplex, int n) {

        java.util.List<Vector3f> vertices = new java.util.ArrayList<>();
        if (n >= 4) {

            vertices.add(simplex[0]);
            vertices.add(simplex[1]);
            vertices.add(simplex[2]);
            vertices.add(simplex[3]);
        } else if (n == 3) {


            Vector3f ab = new Vector3f(simplex[1]).sub(simplex[0]);
            Vector3f ac = new Vector3f(simplex[2]).sub(simplex[0]);
            Vector3f normal = new Vector3f(ab).cross(ac).normalize();
            vertices.add(simplex[0]);
            vertices.add(simplex[1]);
            vertices.add(simplex[2]);
            Vector3f p = support(a, b, normal);
            vertices.add(p);
        } else {

            return new Vector3f(0, 0.1f, 0);
        }


        java.util.List<int[]> faces = new java.util.ArrayList<>();
        faces.add(new int[]{0, 1, 2});
        faces.add(new int[]{0, 3, 1});
        faces.add(new int[]{0, 2, 3});
        faces.add(new int[]{1, 3, 2});

        Vector3f bestNormal = new Vector3f(0, 1, 0);
        float bestDist = Float.MAX_VALUE;

        for (int iter = 0; iter < 64; iter++) {

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
                normal.div((float) Math.sqrt(len2));

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


            Vector3f p = support(a, b, bestFaceNormal);
            float d = p.dot(bestFaceNormal);


            bestNormal = bestFaceNormal;
            bestDist = minDist;
            if (d - minDist < 1e-6f) {

                break;
            }


            int newIdx = vertices.size();
            vertices.add(p);


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


                Vector3f toNew = new Vector3f(p).sub(v0);
                if (normal.dot(toNew) > 0) {

                    addBoundaryEdge(boundaryEdges, f[0], f[1]);
                    addBoundaryEdge(boundaryEdges, f[1], f[2]);
                    addBoundaryEdge(boundaryEdges, f[2], f[0]);
                } else {
                    newFaces.add(f);
                }
            }


            for (int[] edge : boundaryEdges) {
                newFaces.add(new int[]{edge[0], edge[1], newIdx});
            }

            faces = newFaces;
        }


        return new Vector3f(bestNormal).mul(bestDist);
    }


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




    public static class BoxShape {
        public final float cx, cy, cz;
        public final float hx, hy, hz;

        public BoxShape(float cx, float cy, float cz, float hx, float hy, float hz) {
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.hx = hx; this.hy = hy; this.hz = hz;
        }


        public Vector3f support(Vector3f d) {
            return new Vector3f(
                cx + (d.x >= 0 ? hx : -hx),
                cy + (d.y >= 0 ? hy : -hy),
                cz + (d.z >= 0 ? hz : -hz)
            );
        }
    }


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
