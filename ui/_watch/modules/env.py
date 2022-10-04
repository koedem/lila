import random
import os
import subprocess
import base64
import argparse
import modules.util as util
from datetime import datetime
from pathlib import Path

# Env.__init__ assumes its location is lila/ui/_watch/modules/env.py
class Env:
    def __init__(self):
        self.args = None  # will be argparse.Namespace
        self.src_path = Path(*Path(__file__).parts[:-3])  # [:-3])

    def set_args(self, args: argparse.Namespace):
        self.args = args

    def random_uid(self) -> str:
        return random.choice(["blah"])

    def random_social_media_links(self) -> list[str]:
        return random.sample([1, 2, 3, 4, 5, 6], 3)


e = Env()

"""
    def get_password_hash(self, uid: str) -> bytes:
      password = self.custom_passwords.get(uid, self.default_password)
      if password in self.hash_cache:
          return self.hash_cache[password]

      result = subprocess.run(
          ["java", "-jar", self.lila_crypt_path],
          stdout=subprocess.PIPE,
          input=password.encode("utf-8"),
      ).stdout
      if result[0] != 68 or result[1] != 68:
          raise Exception(f"bad output from {self.lila_crypt_path}")
      hash = result[2:]
      self.hash_cache[password] = hash
      return hash

    def next_id(self, key_obj, num_bytes: int = 6) -> str:
      seed = self.seeds.setdefault(key_obj.__name__, 1)
      self.seeds[key_obj.__name__] = seed + 1
      return base64.b64encode(seed.to_bytes(num_bytes, "big")).decode(
          "ascii"
      )

    def _read_strings(self, name: str) -> list[str]:
        with open(os.path.join(self.data_path, name), "r") as f:
            return [
                s.strip()
                for s in f.read().splitlines()
                if s and not s.lstrip().startswith("#")
            ]

    def _read_bson(self, filename: str) -> list[dict]:
        with open(os.path.join(self.data_path, filename), "rb") as f:
            return bson.decode_all(f.read())
"""
