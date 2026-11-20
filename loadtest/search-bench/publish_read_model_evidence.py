"""Copy measured formal runs into reviewable docs evidence, compressing large raw logs.
Usage: python publish_read_model_evidence.py RESULTS_ROOT EVIDENCE_ROOT
Does not delete or modify source results. Synthetic benchmark data only.
"""
import gzip
import shutil
import sys
from pathlib import Path

LABELS = ['baseline-default-final', 'baseline-cards-ready', 'required-default', 'required-cutover-10']
source, target = map(Path, sys.argv[1:3])
for label in LABELS:
    origin = source / label
    assert (origin / 'environment.json').exists(), f'missing {label}'
    destination = target / label
    for entry in origin.rglob('*'):
        if not entry.is_file() or entry.name.endswith('.jar'):
            continue
        relative = entry.relative_to(origin)
        output = destination / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        if entry.suffix in ['.log', '.jsonl']:
            with entry.open('rb') as reader, gzip.GzipFile(str(output) + '.gz', 'wb', mtime=0) as writer:
                shutil.copyfileobj(reader, writer)
        else:
            shutil.copyfile(entry, output)
    print(f'published {label}')
