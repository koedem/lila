/*import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from '@rollup/plugin-typescript';
import * as rollup from 'rollup';
import * as fs from 'fs';
import * as path from 'path';
import * as ps from 'process';*/
import * as bleep from './bleep';

export const tscWatch = () => {
  const tsconfig:any = {
    'files': [],
    'references': []
  }
  //bleep.modules.forEach((mod:bleep.Module) => tsconfig.references.push({'path': `../${mod.name}`}));
  //fs.writeFileSync(bleep.inDir + '/@build/tsconfig.ref.json', JSON.stringify(tsconfig),)
  //spawn('node tsc --incremental --build ./@build/tsconfig.ref.json --watch');

  //for (const m in bleep.modules)
  //console.log(m);
}