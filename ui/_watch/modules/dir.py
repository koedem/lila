import random
import os
import subprocess
import base64
import argparse
import modules.util as util
from modules.env import e
from datetime import datetime
from pathlib import Path


def walk(p: Path):
    for node in p.iterdir():
        print(str(node))


# files used in DataSrc.__init__ are found in spamdb/data folder
# class Dir:
#    def __init__(self):
#        self.blah = None

ignore = [
    "@build",
    "@types",
    "_watch",
]
