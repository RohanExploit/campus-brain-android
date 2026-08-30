import json
import pathlib

graph_dir = pathlib.Path('data/tenants/tenant_1/graph')
json_path = graph_dir / 'extracted_entities.json'

with open(json_path, 'r', encoding='utf-8') as f:
    data = json.load(f)

nodes = data.get('nodes', [])
edges = data.get('edges', [])

file_map = {}

for node in nodes:
    sf = node.get('source_file', 'unknown')
    if sf not in file_map:
        file_map[sf] = {'nodes': [], 'edges': []}
    file_map[sf]['nodes'].append(node)

for edge in edges:
    sf = edge.get('source_file', 'unknown')
    if sf not in file_map:
        file_map[sf] = {'nodes': [], 'edges': []}
    file_map[sf]['edges'].append(edge)

md_content = "# Extraction Spot-Check (Real Documents Only)\n\n"

summary_table = "| Source File | Nodes | Edges | Edges:Nodes Ratio |\n"
summary_table += "| :--- | :--- | :--- | :--- |\n"

for sf, content in file_map.items():
    n_count = len(content['nodes'])
    e_count = len(content['edges'])
    ratio = round(e_count / n_count, 2) if n_count > 0 else 0
    summary_table += f"| `{sf}` | {n_count} | {e_count} | **{ratio}** |\n"

md_content += summary_table + "\n---\n\n"

for sf, content in file_map.items():
    md_content += f"## Document: `{sf}`\n\n"
    md_content += f"**Total Nodes:** {len(content['nodes'])} | **Total Edges:** {len(content['edges'])}\n\n"

    md_content += "### Sample Nodes (First 5)\n```json\n"
    md_content += json.dumps(content['nodes'][:5], indent=2)
    md_content += "\n```\n\n"

    md_content += "### Sample Edges (First 5)\n```json\n"
    md_content += json.dumps(content['edges'][:5], indent=2)
    md_content += "\n```\n\n---\n\n"

spotcheck_path = pathlib.Path('extraction_spotcheck.md')
spotcheck_path.write_text(md_content, encoding='utf-8')

print("Spot-check generated successfully.")
