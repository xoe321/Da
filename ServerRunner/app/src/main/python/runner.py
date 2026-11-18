import runpy, sys, os, traceback

def run(path, logpath):
    f = open(logpath, "a", buffering=1)
    sys.stdout = f
    sys.stderr = f
    sys.argv = [path]
    os.chdir(os.path.dirname(path))
    try:
        runpy.run_path(path, run_name="__main__")
    except BaseException:
        traceback.print_exc()
