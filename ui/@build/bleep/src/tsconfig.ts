import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from '@rollup/plugin-typescript';
import * as rollup from 'rollup';
import * as fs from 'fs';
import * as path from 'path';
import * as ps from 'process';
import { modules, launchDeps, LilaModule, tsconfigDir, uidir } from './bleep';

export const makeBleepConfig = () => {//cfgPaths: string[]) => {
  const tsc: string[] = [];

  modules.forEach(mod => { // do this first
    launchDeps.get(mod.name)?.forEach(dep => {
      const depMod = modules.get(dep);
      if (depMod?.tscOptions && !depMod.tscOptions?.includes('composite'))
        depMod.tscOptions!.push('composite');
    });
  });

  modules.forEach(mod => {
    if (mod.tscOptions) tsc.push(makeTsConfig(mod,true))
    else makeTsConfig(mod,false);
  });
  const cfg:any = {};
  cfg.files = [];
  cfg.compilerOptions = {
    //rootDirs: Array.from(tsc.map(t => path.resolve(uidir,t.substring(0, t.indexOf('.')))))
  }
  cfg.references = Array.from(tsc.map(p => { return { path: p } }));
  fs.writeFileSync(path.resolve(tsconfigDir, 'bleep.tsconfig.json'), JSON.stringify(cfg));
  console.log(cfg);
}

// still need to do site BS
const makeTsConfig = (mod: LilaModule, doDeps = false): string => {
  const fixMe = ["include","exclude","outDir","src","baseUrl","extends", "path"]
  const resolvePaths = (o:any, forceAll = false): any => {
    for (const prop in o) {
      const val = o[prop];
      if (forceAll || fixMe.includes(prop)) {
        if (typeof val == 'string') o[prop] = path.resolve(mod.root, val);
        else if (Array.isArray(val)) o[prop] = o[prop].map((p:string) => path.resolve(mod.root, p)); 
      }
      else if (typeof val === 'object' && !Array.isArray(val)) {
        o[prop] = resolvePaths(val, prop == 'paths'); // resolve all values in 'paths' element
      }
    }
    return o;
  };
  let src: any;
  try { src = JSON.parse(fs.readFileSync(path.resolve(mod.root, 'tsconfig.json'),'utf8')); }
  catch (e) {
    return '';
  }
  src = resolvePaths(src);

  if (!('compilerOptions' in src)) src.compilerOptions = {}
  
  mod.tscOptions?.forEach(option => src.compilerOptions[option] = true);
  // force add incremental here?
    src.compilerOptions.rootDir =  path.resolve(uidir,mod.name,'src');

  const deps = launchDeps.get(mod.name) ;

  if (deps && doDeps) {
    src['references'] = Array.from(deps.map(dep => {
      return { path: `${dep}.tsconfig.json` }
    }));
  }
  const cfgName = `${mod.name}.tsconfig.json`;
  fs.writeFileSync(
    path.resolve(tsconfigDir, cfgName), 
    JSON.stringify(src)
  );
  return cfgName;
}
