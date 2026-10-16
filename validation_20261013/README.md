## 代码

`results/resumed-20261014/records.jsonl` 是完整原始记录，包含逐字节保留的旧记录前缀和标记 segment=resumed 的新记录。`analysis.json` 为报告统计依据：注册采用历史100个样本，链路耗时只使用本次1000个样本。`summary.json` 的混合阶段统计不可替代此口径。continuous与pipeline记录同一链路的阶段及整体计时，不可相加计数。

`environment.json`、`groups.json`、`policies.json`、`revocation_targets.json` 保存配置与实验设计；原主机信息见 `results/full-01/hardware.json`。`manifest_full_20261014.json` 保存正式报告、脚本和证据的SHA-256。

`scripts/resume_acceptance.py` 为本次驱动，`scripts/analyze.py` 复算指标，`scripts/build_full_report.py` 生成正式报告。smoke-01只用于小规模预检。extend_revocation.py未用于本次续跑，本次驱动直接执行全部100轮。

## 重建

在项目根目录执行，报告构建需含lxml的Python环境：

```sh
python3 validation_20261013/scripts/analyze.py validation_20261013/results/resumed-20261014
python3 validation_20261013/scripts/build_full_report.py --run resumed-20261014
```

需重新运行密码测试时使用新的运行编号，保留已有结果：

```sh
python3 validation_20261013/scripts/resume_acceptance.py --run resumed-repeat --resume-from validation_20261013/results/full-01 --port 8980
python3 validation_20261013/scripts/analyze.py validation_20261013/results/resumed-repeat
```
