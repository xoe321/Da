import runpy, sys, os, traceback

def run(path, logpath, port=8080):
    f = open(logpath, "a", buffering=1)
    sys.stdout = f
    sys.stderr = f
    sys.argv = [path]
    os.environ["PORT"] = str(port)
    base = os.path.dirname(path)
    data = os.path.join(base, "data")          # persistent storage for .sqlite3 files
    os.makedirs(data, exist_ok=True)
    os.environ["HOME"] = base
    os.environ["DATA_DIR"] = data
    os.environ["DB_PATH"] = os.path.join(data, "data.sqlite3")
    os.chdir(os.path.dirname(path))
    sys.path.insert(0, os.path.dirname(path))
    try:
        runpy.run_path(path, run_name="__main__")
    except BaseException:
        traceback.print_exc()
