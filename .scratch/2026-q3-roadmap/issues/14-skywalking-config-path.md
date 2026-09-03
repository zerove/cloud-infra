# P3-04: skywalking-oap config 路径问题 (升级 10.x 才能解决)

Status: closed (合并到 issue 17: P3-14 skywalking 升级 11.0.0)
Priority: P3
Owner: TBD
Module: deploy/skywalking/

## 总结

本 issue 9-02 创建时假设问题是 volume 挂载路径错。
9-03 探查后发现真正根因: **9.7.0 镜像 vs application.yml (11.0.0 风格) 不匹配**。
9-7-0 镜像不含 `receiver-runtime-rule`, `dsl-debugging`, `ui-management`, `inspect`, `admin-server`, `status` 这 6 个 admin modules (10.x+ 才有)。

详细记录在: `.scratch/2026-q3-roadmap/issues/17-skywalking-upgrade-11.md`
