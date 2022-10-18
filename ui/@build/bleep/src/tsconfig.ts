import * as fs from 'fs';
import * as path from 'path';
import { modules, bleepLog, moduleDeps, LichessModule, tsconfigDir, uiDir } from './build';

export const makeBleepConfig = async () => {//cfgPaths: string[]) => {
  const tsc: string[] = [];

  modules.forEach(mod => { // do this first
    moduleDeps.get(mod.name)?.forEach(dep => {
      const depMod = modules.get(dep);
      if (depMod?.tscOptions && !depMod.tscOptions?.includes('composite'))
        depMod.tscOptions!.push('composite');
    });
  });

  for (const mod of modules.values()) {
    if (mod.tscOptions) tsc.push(await makeTsConfig(mod,true))
    else await makeTsConfig(mod,false);
  }
  const cfg:any = {};
  cfg.files = [];
  cfg.compilerOptions = {}
  cfg.references = tsc.map(p => ({ path: p }));
  await fs.promises.writeFile(
    path.resolve(tsconfigDir, 'bleep.tsconfig.json'), 
    JSON.stringify(cfg)
  );
}

const makeTsConfig = async (mod: LichessModule, doDeps = false): Promise<string> => {
  const fixMe = ["include","exclude","outDir","src","baseUrl","extends", "path"]
  const resolvePaths = (o:any, forceAll = false): any => {
    for (const key in o)
      if (forceAll || fixMe.includes(key))
        if (typeof o[key] == 'string') 
          o[key] = path.resolve(mod.root, o[key]);
        else if (Array.isArray(o[key])) o[key] = 
          o[key].map((el: any) => resolvePaths(el));

      else if (typeof o[key] === 'object' && !Array.isArray(o[key]))
        o[key] = resolvePaths(o[key], key == 'paths'); // resolve all values in 'paths' element

    return o;
  };
  const config = resolvePaths(JSON.parse(
    await fs.promises.readFile(path.resolve(mod.root, 'tsconfig.json'), 'utf8')
  ));
  if (!('include' in config)) config.include = [path.resolve(uiDir,mod.name,'src')];
  if (!('compilerOptions' in config)) config.compilerOptions = {};
  config.compilerOptions.rootDir = path.resolve(uiDir,mod.name,'src');
  mod.tscOptions?.forEach(option => config.compilerOptions[option] = true);

  const deps = moduleDeps.get(mod.name) ;
  if (doDeps && deps)
    config['references'] = deps.map(dep => ({ path: `${dep}.tsconfig.json` }));

  const configName = `${mod.name}.tsconfig.json`;

  await fs.promises.writeFile(
    path.resolve(tsconfigDir, configName), 
    JSON.stringify(config)
  );
  return configName;
}
