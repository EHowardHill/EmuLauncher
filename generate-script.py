import os

# Use 'r' before the string to handle Windows backslashes correctly
def process(path, name):
    final = ""

    # os.walk yields a 3-tuple: (current_folder, list_of_subfolders, list_of_files)
    for root, dirs, files in os.walk(path):
        for file in files:
            
            # 1. Logic to skip specific files
            if file in [".DS_Store"]:
                continue
            
            # 2. Construct the full absolute path
            full_path = os.path.join(root, file)
            
            # 3. Construct a relative path (cleaner for reading)
            # This makes the header "com\gnimble\typewriter\MyFile.java" instead of "C:\Users..."
            relative_path = os.path.relpath(full_path, path)

            try:
                # 'errors="replace"' prevents crashing if a file has weird characters
                with open(full_path, "r", encoding="utf-8", errors="replace") as f:
                    content = f.read()
                    final += f"{relative_path}:\n{content}\n\n"
            except Exception as e:
                print(f"Skipping {file} (likely not a text file or permission error).")

    with open("source-" + name + ".txt", "w", encoding="utf-8") as f:
        f.write(final.strip())

process(r"C:\Users\ethan\Documents\GitHub\EmuLauncher\app\src\main\java\com\cinemint\emulauncher", "emulauncher")
process(r"C:\Users\ethan\Documents\GitHub\EmuLauncher\app\src\main\res\layout", "res")