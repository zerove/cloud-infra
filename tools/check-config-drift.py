#!/usr/bin/env python3
"""
Nacos 配置漂移检测脚本
对比 Nacos 实际配置与 deploy/config/*.yaml 源文件差异

用法：
  python3 tools/check-config-drift.py [--nacos-url http://localhost:8848] [--config-dir deploy/config]
"""

import argparse
import hashlib
import json
import sys
import subprocess
from pathlib import Path
from typing import Dict, List, Tuple
import yaml

try:
    import requests
except ImportError:
    print("请先安装 requests: pip3 install requests pyyaml")
    sys.exit(1)


class NacosConfigDriftChecker:
    def __init__(self, nacos_url: str, config_dir: Path):
        self.nacos_url = nacos_url.rstrip('/')
        self.config_dir = config_dir
        self.session = requests.Session()
        self.session.headers.update({'Content-Type': 'application/x-www-form-urlencoded'})
    
    def get_nacos_config_list(self) -> List[Dict]:
        """获取 Nacos 所有配置列表（分页）"""
        all_configs = []
        page_no = 1
        page_size = 100
        
        while True:
            url = f"{self.nacos_url}/nacos/v1/cs/configs"
            params = {'pageNo': page_no, 'pageSize': page_size}
            try:
                resp = self.session.get(url, params=params, timeout=10)
                resp.raise_for_status()
                data = resp.json()
                page_items = data.get('pageItems', [])
                if not page_items:
                    break
                all_configs.extend(page_items)
                if len(page_items) < page_size:
                    break
                page_no += 1
            except Exception as e:
                print(f"获取配置列表失败: {e}")
                break
        
        return all_configs
    
    def get_nacos_config(self, data_id: str, group: str = "DEFAULT_GROUP") -> Tuple[str, str]:
        """获取单个配置内容及 MD5"""
        url = f"{self.nacos_url}/nacos/v1/cs/configs"
        params = {'dataId': data_id, 'group': group}
        try:
            resp = self.session.get(url, params=params, timeout=10)
            if resp.status_code == 404:
                return None, None
            resp.raise_for_status()
            content = resp.text
            md5 = hashlib.md5(content.encode('utf-8')).hexdigest()
            return content, md5
        except Exception as e:
            print(f"获取配置 {data_id} 失败: {e}")
            return None, None
    
    def load_local_config(self, filename: str) -> Tuple[str, str]:
        """加载本地配置文件"""
        filepath = self.config_dir / filename
        if not filepath.exists():
            return None, None
        try:
            content = filepath.read_text(encoding='utf-8')
            md5 = hashlib.md5(content.encode('utf-8')).hexdigest()
            return content, md5
        except Exception as e:
            print(f"读取本地文件 {filepath} 失败: {e}")
            return None, None
    
    def compare_yaml(self, nacos_content: str, local_content: str) -> Dict:
        """对比两个 YAML 内容，返回差异"""
        try:
            nacos_data = yaml.safe_load(nacos_content) if nacos_content else {}
            local_data = yaml.safe_load(local_content) if local_content else {}
            
            # 扁平化对比
            diffs = self._diff_dict(nacos_data, local_data, prefix="")
            return {
                'equal': len(diffs) == 0,
                'diffs': diffs
            }
        except Exception as e:
            return {
                'equal': False,
                'diffs': [f"YAML 解析失败: {e}"],
                'parse_error': True
            }
    
    def _diff_dict(self, d1: Dict, d2: Dict, prefix: str) -> List[str]:
        """递归对比字典"""
        diffs = []
        all_keys = set(d1.keys()) | set(d2.keys())
        
        for key in sorted(all_keys):
            path = f"{prefix}.{key}" if prefix else key
            v1 = d1.get(key)
            v2 = d2.get(key)
            
            if key not in d1:
                diffs.append(f"+ {path}: {v2} (仅本地存在)")
            elif key not in d2:
                diffs.append(f"- {path}: {v1} (仅 Nacos 存在)")
            elif isinstance(v1, dict) and isinstance(v2, dict):
                diffs.extend(self._diff_dict(v1, v2, path))
            elif v1 != v2:
                diffs.append(f"~ {path}: Nacos={v1} vs Local={v2}")
        
        return diffs
    
    def check_drift(self) -> Dict:
        """执行漂移检测"""
        nacos_configs = self.get_nacos_config_list()
        local_files = list(self.config_dir.glob("*.yaml"))
        
        nacos_map = {cfg['dataId']: cfg for cfg in nacos_configs}
        local_map = {f.name: f for f in local_files}
        
        all_data_ids = set(nacos_map.keys()) | set(local_map.keys())
        
        results = {
            'summary': {'total': 0, 'equal': 0, 'drift': 0, 'missing_nacos': 0, 'missing_local': 0},
            'details': []
        }
        
        for data_id in sorted(all_data_ids):
            results['summary']['total'] += 1
            
            nacos_cfg = nacos_map.get(data_id)
            local_file = local_map.get(data_id)
            
            if not nacos_cfg:
                results['summary']['missing_nacos'] += 1
                results['details'].append({
                    'dataId': data_id,
                    'status': 'MISSING_NACOS',
                    'message': '仅存在于本地 deploy/config，Nacos 中不存在'
                })
                continue
            
            if not local_file:
                results['summary']['missing_local'] += 1
                results['details'].append({
                    'dataId': data_id,
                    'status': 'MISSING_LOCAL',
                    'message': '仅存在于 Nacos，本地 deploy/config 缺失'
                })
                continue
            
            # 两边都有，对比内容
            nacos_content, nacos_md5 = self.get_nacos_config(data_id, nacos_cfg.get('group', 'DEFAULT_GROUP'))
            local_content, local_md5 = self.load_local_config(data_id)
            
            if nacos_md5 == local_md5:
                results['summary']['equal'] += 1
                results['details'].append({
                    'dataId': data_id,
                    'status': 'EQUAL',
                    'md5': nacos_md5
                })
            else:
                results['summary']['drift'] += 1
                compare_result = self.compare_yaml(nacos_content, local_content)
                results['details'].append({
                    'dataId': data_id,
                    'status': 'DRIFT',
                    'nacosMd5': nacos_md5,
                    'localMd5': local_md5,
                    'diffs': compare_result.get('diffs', [])
                })
        
        return results


def main():
    parser = argparse.ArgumentParser(description="Nacos 配置漂移检测")
    parser.add_argument('--nacos-url', default='http://localhost:8848', help='Nacos 地址')
    parser.add_argument('--config-dir', default='deploy/config', help='本地配置目录')
    parser.add_argument('--output', choices=['json', 'text'], default='text', help='输出格式')
    parser.add_argument('--fail-on-drift', action='store_true', help='有漂移时返回非零退出码')
    args = parser.parse_args()
    
    checker = NacosConfigDriftChecker(args.nacos_url, Path(args.config_dir))
    print(f"正在检测配置漂移... Nacos: {args.nacos_url}, 本地目录: {args.config_dir}")
    
    results = checker.check_drift()
    
    if args.output == 'json':
        print(json.dumps(results, ensure_ascii=False, indent=2))
    else:
        s = results['summary']
        print(f"\n=== 配置漂移检测报告 ===")
        print(f"总计: {s['total']}  一致: {s['equal']}  漂移: {s['drift']}  仅本地: {s['missing_nacos']}  仅 Nacos: {s['missing_local']}")
        
        for detail in results['details']:
            status = detail['status']
            if status == 'EQUAL':
                print(f"  ✓ {detail['dataId']}: 一致")
            elif status == 'MISSING_NACOS':
                print(f"  ⚠ {detail['dataId']}: 仅本地存在（Nacos 缺失）")
            elif status == 'MISSING_LOCAL':
                print(f"  ⚠ {detail['dataId']}: 仅 Nacos 存在（本地缺失）")
            elif status == 'DRIFT':
                print(f"  ✗ {detail['dataId']}: 漂移")
                for diff in detail.get('diffs', [])[:10]:  # 限制显示前 10 条差异
                    print(f"      {diff}")
                if len(detail.get('diffs', [])) > 10:
                    print(f"      ... 还有 {len(detail['diffs']) - 10} 处差异")
    
    if args.fail_on_drift and results['summary']['drift'] > 0:
        sys.exit(1)
    
    if results['summary']['missing_nacos'] > 0 or results['summary']['missing_local'] > 0:
        sys.exit(2)


if __name__ == '__main__':
    main()