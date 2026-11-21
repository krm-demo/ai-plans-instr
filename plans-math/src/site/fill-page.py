"""Fills in index.html of the GitHub Pages (run by the plans-math workflow, not published):
the status list (written by EquilibDocs from Zone), the test and coverage numbers, the commit; writes the badge JSON.
   python3 fill-page.py <pages dir> <statuses.html> <commit sha>"""
import csv, datetime, glob, json, os, sys
import xml.etree.ElementTree as ET

pages, statuses, sha = sys.argv[1], sys.argv[2], sys.argv[3]
tests = failed = skipped = 0
for f in glob.glob('plans-math/target/surefire-reports/TEST-*.xml'):
    r = ET.parse(f).getroot()
    tests += int(r.get('tests', 0)); skipped += int(r.get('skipped', 0))
    failed += int(r.get('failures', 0)) + int(r.get('errors', 0))
missed = covered = 0
jacoco = 'plans-math/target/site/jacoco/jacoco.csv'
if os.path.exists(jacoco):
    for row in csv.DictReader(open(jacoco)):
        missed += int(row['LINE_MISSED']); covered += int(row['LINE_COVERED'])
cov = 100.0 * covered / max(missed + covered, 1)
passed = tests - failed - skipped

os.makedirs(f'{pages}/badges', exist_ok=True)
json.dump({"schemaVersion": 1, "label": "JUnit tests", "message": f"{passed} passed" + (f", {failed} failed" if failed else ""),
           "color": "brightgreen" if not failed else "red"}, open(f'{pages}/badges/tests.json', 'w'))
json.dump({"schemaVersion": 1, "label": "JaCoCo coverage", "message": f"{cov:.1f}%",
           "color": "brightgreen" if cov >= 80 else "yellow" if cov >= 60 else "orange"}, open(f'{pages}/badges/coverage.json', 'w'))
print(f"tests {tests}, passed {passed}, failed {failed}, skipped {skipped}; lines {covered}/{missed + covered} = {cov:.1f}%")

index = f'{pages}/index.html'
html = open(index, encoding='utf-8').read()
html = (html.replace('<!-- statuses -->', open(statuses, encoding='utf-8').read())
        .replace('{{tests}}', f'{passed} JUnit tests passed' + (f', {failed} failed' if failed else ''))
        .replace('{{coverage}}', f'{cov:.1f}%')
        .replace('{{commit}}', sha).replace('{{commit7}}', sha[:7])
        .replace('{{date}}', datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC')))
open(index, 'w', encoding='utf-8').write(html)
