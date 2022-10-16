import * as rup from 'rollup';
import * as fs from 'fs';
import * as path from 'path';
import * as ps from 'process';
import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from '@rollup/plugin-typescript';

export const rootDir = '/Users/gamblej/ws/lichess/lila-local';
export const outDir = path.resolve(rootDir,'public/compiled');
export const uidir = path.resolve(rootDir,'ui');
export const bleepDir = path.resolve(uidir,'@build/bleep');
export const tsconfigDir = path.resolve(bleepDir,'.tsconfig');

const o = {
  dirName: 'learn',
  input: 'src/main.ts',
  hasTsConfig: true,
  output: 'learn',
  modName: 'LichessLearn'
}
console.log(ps.memoryUsage.rss()/(1024*1024)+' MB');
const modDir = path.resolve(uidir, o.dirName);

rup.watch( {
  input: path.resolve(modDir, o.input),
  plugins: o.hasTsConfig ? [
    typescript(
      {
        //filterRoot: modDir,
        tsconfig: path.resolve(modDir, 'tsconfig.json')
        //tsconfig: path.resolve(bleepDir, '.tsconfig', `${o.dirName}.tsconfig.json`)
      }
    ),
    resolve(),
    commonjs({extensions: ['.js']})
  ] : [],
  output: {
    format: 'iife',
    name: o.modName ? o.modName : o.dirName,
    file: path.resolve(outDir, `${o.output}.js`),
    generatedCode: { preset: 'es2015', constBindings: false },
  },
  /*watch: {
    //exclude: 'node_modules/**',
    clearScreen: false
  }*/
} ).on('event', e => {
    console.log(e)
    switch (e.code) {
      case 'END':
        console.log('idle..');
        
        break;
      case 'START':
        break;
      case 'BUNDLE_START':
        break;
      case 'BUNDLE_END':

        if (e.result) e.result.close();
        break;
      case 'ERROR':
        console.log(e.error);
    }
  });

