import os
import re

def main():
    target_dir = 'apk_unpacked'
    for root, dirs, files in os.walk(target_dir):
        for file in files:
            if not file.endswith('.smali'):
                continue
            filepath = os.path.join(root, file)
            with open(filepath, 'r') as f:
                lines = f.readlines()
            for i, line in enumerate(lines):
                if 'Lcom/juanvision/bussiness/ad/ADService;->obtain' in line:
                    # Find the move-result-object line
                    return_reg = None
                    for j in range(i+1, min(i+5, len(lines))):
                        m = re.search(r'move-result-object\s+(v\d+|p\d+)', lines[j])
                        if m:
                            return_reg = m.group(1)
                            break
                    if return_reg:
                        # Check if there is a null check (if-eqz or if-nez) in the next 15 lines
                        # or if it's stored in a field and later used. We can just print the snippet.
                        has_check = False
                        for j in range(i+1, min(i+15, len(lines))):
                            if f'if-eqz {return_reg}' in lines[j] or f'if-nez {return_reg}' in lines[j]:
                                has_check = True
                                break
                        if not has_check:
                            print(f'NO CHECK FOUND in {filepath}:{i+1}')
                            for k in range(i, min(i+15, len(lines))):
                                print(lines[k].strip())
                            print('-' * 40)

if __name__ == '__main__':
    main()
