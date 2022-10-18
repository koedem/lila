import * as fs from 'fs';
import * as cps from 'child_process';
import * as path from 'path';
import pluginCopy from 'rollup-plugin-copy';
import pluginReplace from '@rollup/plugin-replace';
import { LichessModule, nodeDir } from './build';
//export { parseModules }; 

export async function parseModules(uidir: string): Promise<LichessModule[]> {
  const modules: LichessModule[] = [];

  for await (const moduleDir of walkModules(uidir)) 
    if (moduleDir != uidir) 
      modules.push(await parseModule(moduleDir))

  return modules;
}

const walkModules = async function*(dirpath: string): AsyncGenerator<string> {
  const walkFilter = ["@build", "@types", "node_modules"];

  const fsNodes = await fs.promises.readdir(dirpath, { withFileTypes: true });
  for (const fsNode of fsNodes) {
    if (walkFilter.includes(fsNode.name) || fsNode.name[0] == '.')  continue;
    
    const fullpath = path.resolve(dirpath, fsNode.name);
    if (fsNode.isDirectory()) yield* walkModules(fullpath);
    else if (fsNode.name == 'package.json') yield dirpath;
  }
}

const parseModule = async (moduleDir:string): Promise<LichessModule> => {
  const pkg = parseObject(
    await fs.promises.readFile(path.resolve(moduleDir,'package.json'), 'utf8')
  );

  const mod: LichessModule = {
    pkg: pkg,
    name: path.basename(moduleDir),
    root: moduleDir,
    build: {pre:[], post:[]},
    hasTsconfig: fs.existsSync(path.join(moduleDir,'tsconfig.json'))
  }
  parseScripts(mod, 'scripts' in pkg ? pkg.scripts : {})
  const rollupConfigPath = path.join(mod.root,'rollup.config.mjs');
  
  if (!fs.existsSync(rollupConfigPath)) return mod; // we're done
  
  mod.rollup = [];
  const rollupConfigStr = await fs.promises.readFile(rollupConfigPath,'utf8');
  const rollupMatch = /rollupProject\((\{.+})\);/s.exec(rollupConfigStr);

  const rollupObj = parseObject(rollupMatch?.length == 2 ? rollupMatch[1]: null);

  for (const key in rollupObj) {
    const cfg = rollupObj[key];
    if (key == 'main' && cfg.output != mod.name) 
      mod.moduleAlias = cfg.output; // analyse == analysisBoard
     
    mod.rollup.push({
      hostMod: mod, 
      input: cfg.input, 
      output: cfg.output,
      importName: cfg.name ? cfg.name : cfg.output, 
      plugins: cfg.plugins,
      onWarn: cfg.onwarn,
      isMain: key == 'main' || cfg.output == mod.name// run more commands from package.json?
    });
  }
  return mod;
}

const replaceValues = { // from site/rollup.config.js
  __info__: JSON.stringify({
    date: new Date(new Date().toUTCString()).toISOString().split('.')[0] + '+00:00',
    commit: cps.execSync('git rev-parse -q HEAD', { encoding: 'utf-8' }).trim(),
    message: cps.execSync('git log -1 --pretty=%s', { encoding: 'utf-8' }).trim(),
  }),
}

const suppressThisIsUndefined = 
  (warning: any, warn: any) => warning.code !== "THIS_IS_UNDEFINED" && warn(warning);

const parseObject = (o: string|null) => {
  const copy = pluginCopy;
  const replace = (_:any) => pluginReplace({
    values: replaceValues,
    preventAssignment: true,
  });
  const dirname = path.dirname;
  const execSync = (_:any,__:any) => ''; // ignore, can't execSync in an eval
  const require = { resolve: (mod: string) => path.resolve(nodeDir, mod) };
  copy, replace, dirname, require, suppressThisIsUndefined, execSync; // suppress unused
  return eval(`(${o})`) || {};
}

// filenames containing spaces in a package.json script must be single quoted otherwise fail
const tokenizeArgs = (argstr:string): string[] => {
  const args: string[] = [];
  const reducer = (a:any[], ch:string) => {
    if (ch != ' ') return ch == "'" ? [a[0], !a[1]] : [a[0] + ch, a[1]]
    if (a[1]) return [a[0] + ' ', true];
    else if (a[0]) args.push(a[0]);
    return ['', false];
  };
  const lastOne = [...argstr].reduce(reducer, ['', false])[0];
  return lastOne ? [...args, lastOne] : args;
}

// go through package json scripts and get what we need from 'compile', 'dev', and deps
// if some other script is necessary, add it to buildScriptKeys

const parseScripts = (module: LichessModule, pkgScripts:any) => {
  const buildScriptKeys = ['deps', 'compile', 'dev']; 
  
  let buildList: string[][] = module.build.pre;
  for (const script in pkgScripts) {
    if (!buildScriptKeys.includes(script)) continue;
    pkgScripts[script].split(/&&/).forEach((cmd: string) => {
      // no need to support || in a script property yet
      const args = tokenizeArgs(cmd.trim());
      if (args[0] == 'tsc')
        module.tscOptions = args
          .flatMap(
            (arg: string) => arg.startsWith('--') ? [arg.substring(2)] : []
          ) // only support flag arguments
      else if (args[0] == 'rollup')
        buildList = module.build.post;
      else if (!['$npm_execpath','yarn','npm'].includes(args[0]))
        buildList.push(args);
    });
  }
}
