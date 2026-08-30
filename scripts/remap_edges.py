import json
import pathlib

chunked_dir = pathlib.Path('data/tenants/tenant_1/chunked')
graph_dir = pathlib.Path('data/tenants/tenant_1/graph')

# Rebuild the file map
chunk_files = list(chunked_dir.glob('*_chunks.json'))
excluded_keywords = ['funsd', 'student', 'Indian_Students_Data']
chunk_files = [f for f in chunk_files if not any(kw in f.name for kw in excluded_keywords)]

chunk_to_file = {}
chunk_count = 0
for cfile in chunk_files:
    with open(cfile, 'r', encoding='utf-8') as f:
        chunks = json.load(f)
    for _ in chunks:
        chunk_to_file[chunk_count] = cfile.name
        chunk_count += 1

with open(graph_dir / 'extracted_entities.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Build a mapping from node_id to source_chunk
node_to_chunk = {}
for node in data.get('nodes', []):
    node_id = node.get('id')
    c_id = node.get('source_chunk')
    if node_id and c_id is not None:
        if node_id not in node_to_chunk:
            node_to_chunk[node_id] = set()
        node_to_chunk[node_id].add(c_id)

file_data = {}
for node in data.get('nodes', []):
    c_id = node.get('source_chunk')
    if c_id is not None and c_id in chunk_to_file:
        fname = chunk_to_file[c_id]
        if fname not in file_data: file_data[fname] = {'nodes': [], 'edges': []}
        file_data[fname]['nodes'].append(node)

for edge in data.get('edges', []):
    # Remap logic
    source_chunks = node_to_chunk.get(edge.get('source', ''), set())
    target_chunks = node_to_chunk.get(edge.get('target', ''), set())

    # Intersection first, if they both appeared in the same chunk
    intersect = source_chunks.intersection(target_chunks)
    if intersect:
        c_id = list(intersect)[0]
    elif source_chunks:
        c_id = list(source_chunks)[0]
    elif target_chunks:
        c_id = list(target_chunks)[0]
    else:
        c_id = None

    if c_id is not None and c_id in chunk_to_file:
        fname = chunk_to_file[c_id]
        if fname not in file_data: file_data[fname] = {'nodes': [], 'edges': []}
        file_data[fname]['edges'].append(edge)

real_files = [
    '1 RAG-MicroSim_ A Hybrid Retrieval-Augmented Generation and Market Micro-Simulation Framework for High-Frequency Trading Analysis_chunks.json',
    'Final  Resarch paper_chunks.json',
    'DOC-20260212-WA0018._chunks.json'
]
synth_files = [
    'HR_Policy_01_chunks.json',
    'Financial_Report_01_chunks.json',
    'Project_Proposal_01_chunks.json',
    'Quarterly_Review_01_chunks.json'
]

md = '# Extraction Spot-Check\n\n'
md += '## REAL DOCUMENTS\n\n'
for rf in real_files:
    if rf in file_data:
        md += f'### {rf}\n'
        md += f'**Nodes: {len(file_data[rf]["nodes"])} | Edges: {len(file_data[rf]["edges"])}**\n\n'
        md += '#### Nodes (Sample)\n```json\n' + json.dumps(file_data[rf]['nodes'][:5], indent=2) + '\n```\n'
        md += '#### Edges (Sample)\n```json\n' + json.dumps(file_data[rf]['edges'][:5], indent=2) + '\n```\n\n'

md += '## SYNTHETIC DOCUMENTS\n\n'
for sf in synth_files:
    if sf in file_data:
        md += f'### {sf}\n'
        md += f'**Nodes: {len(file_data[sf]["nodes"])} | Edges: {len(file_data[sf]["edges"])}**\n\n'
        md += '#### Nodes (Sample)\n```json\n' + json.dumps(file_data[sf]['nodes'][:5], indent=2) + '\n```\n'
        md += '#### Edges (Sample)\n```json\n' + json.dumps(file_data[sf]['edges'][:5], indent=2) + '\n```\n\n'

pathlib.Path('extraction_spotcheck.md').write_text(md, encoding='utf-8')
