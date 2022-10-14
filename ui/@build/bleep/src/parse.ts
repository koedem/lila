import * as fs from 'fs';
import * as path from 'path';
import { Module } from './bleep';

export default parseModules; 

const walkModules = async function*(dirpath: string): AsyncGenerator<string> {
  const walkFilter = 
    [/*"build", "css", "src", "dist", "types",*/ "@build", "@types", "node_modules"];

  const fsNodes = await fs.promises.readdir(dirpath, { withFileTypes: true });
  for (const fsNode of fsNodes) {
    if (walkFilter.includes(fsNode.name) || fsNode.name[0] == '.')  continue;
    
    const fullpath = path.resolve(dirpath, fsNode.name);
    if (fsNode.isDirectory()) yield* walkModules(fullpath);
    else if (fsNode.name == 'package.json') yield dirpath;
  }
}

const rollupRe = /rollupProject\((\{.+})\);/s

const parseModule = async (moduleDir:string): Promise<Module> => {
  const pkg = parseObject(
    await fs.promises.readFile(path.resolve(moduleDir,'package.json'), 'utf8')
  );

  const mod: Module = {
    pkg: pkg,
    name: path.basename(moduleDir),
    root: moduleDir
  }
  parseScriptArgs(mod, 'scripts' in pkg ? pkg.scripts : {})


          mod.rollup = [];

  const rollupConfigPath = path.join(mod.root,'rollup.config.mjs');
  if (!fs.existsSync(rollupConfigPath)) {
    mod.rollup.push({mod: mod.name, name: mod.name, input: 'src/*'});
    return mod; // let rollup do tsc, otherwise we're done
  }
  const rollup = stripProperty( 
    matchGroupOne(rollupRe, await fs.promises.readFile(rollupConfigPath,'utf8')), 
    'plugins'
  );
  if (rollup.prop) { 
    /* do some shit for site here */ 
  }
  const obj = parseObject(rollup?.objMinusProp);

  //mod.rollup = [];

  for (const key in obj) {
    const cfg = obj[key];
    if (key == 'main' && cfg.output != mod.name) mod.rollupAlias = cfg.output
    mod.rollup.push({
      mod: mod.name, 
      name: cfg.name ? cfg.name : cfg.output, 
      input: cfg.input, 
      output: cfg.output
    });
  }
  return mod;
}

// filenames with spaces in a package.json prop must be single quoted
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

// go through package json scripts and get what we need from 'compile' and 'dev'
const parseScriptArgs = (module: Module, pkgScripts:any) => {
  // if some other script is necessary, add it to buildScriptKeys
  const buildScriptKeys = ['compile', 'dev']; 
  const buildList: string[][] = []
  for (const script in pkgScripts) {
    if (!buildScriptKeys.includes(script)) continue;
    pkgScripts[script].split('&&').forEach((cmd: string) => {
      const args = tokenizeArgs(cmd.trim()).filter((x) => x != '$npm_execpath');
      if (args[0] == 'tsc') { // we handle this, but store off the options
        module.tscOptions = args
          .flatMap((arg: string) => arg.startsWith('--') ? [arg.substring(2)] : [])
      }
      else if (args[0] != 'rollup') { // we handle this too, no need
        buildList.push(args)
      }
    });
  }
  if (buildList.length) module.build = buildList;
}

const matchGroupOne = (re: RegExp, str: string):string|null => {
  const match = re.exec(str);
  return match?.length == 2 ? match[1] : null;
}

// this will fail if an object property contains mismatched {}[] characters
// escaped or wrapped in quotes.

const stripProperty = (obj: string|null, propName: string)
: {objMinusProp: string, prop: string} => {
  obj = obj || '';
  const res = {objMinusProp: obj, prop: ''};
  const re = new RegExp(`[{[,]\\s*${propName}\\s*:\\s*`, 'g');
  const match = re.exec(obj);
  if (!match) return res;

  const stripFrom = match.index + match[0].indexOf(propName);
  let cursor = re.lastIndex, nesting = 0;

  while (cursor < obj.length) {
    const ch = obj[cursor++];
    if (ch == '{' || ch == '[') nesting++;
    else if ((ch == '}' || ch == ']') && --nesting < 0) break;
    else if (ch == ',' && nesting == 0) break;
    res.prop += ch;
  }
  res.objMinusProp = obj.substring(0, stripFrom) + obj.substring(cursor + nesting);
  return res;
}

export const parseObject = (o: string|null): any => Function(
  "'use strict';"
  + "const suppressThisIsUndefined = undefined;" // classic 
  + "return " + o
)();

async function parseModules(uidir: string): Promise<Module[]> {
  const modules: Module[] = [];

  for await (const moduleDir of walkModules(uidir)) 
    if (moduleDir != uidir) 
      modules.push(await parseModule(moduleDir))

  return modules;
}

