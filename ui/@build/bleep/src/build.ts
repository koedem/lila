import * as rup from 'rollup';
import * as fs from 'fs';
import * as path from 'path';
import * as ps from 'process';
import * as cps from 'child_process';
import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from '@rollup/plugin-typescript';
import { parseModules } from './parse';
import { makeBleepConfig } from './tsconfig';

export const rootDir = '/Users/gamblej/ws/lichess/lila-local';
export const nodeDir = path.resolve(rootDir, 'node_modules');
export const outDir = path.resolve(rootDir, 'public/compiled');
export const uiDir = path.resolve(rootDir, 'ui');
export const bleepDir = path.resolve(uiDir, '@build/bleep');
export const tsconfigDir = path.resolve(bleepDir, '.tsconfig');
export const moduleDeps = new Map<string, string[]>();
export let modules: Map<string, LichessModule>;

const cfg = {
  color: true,
}
let gulp: cps.ChildProcess;
let tsc: cps.ChildProcess;
let watcher: rup.RollupWatcher;
let deferred: () => void;

const filterMods = ['tutor'];

// rename LichessModule - lila is the server
export type LichessModule = {
  name: string, // dirname relative to lila/ui, usually the module name
  root: string, // absolute path to package.json parentdir (module root)
  moduleAlias?: string, // analysisBoard, good times
  pkg: any, // the entire package.json object
  build: {pre: string[][], post: string[][]}, // additional build steps from package.json
  hasTsconfig?: boolean,
  tscOptions?: string[],
  rollup?: LichessRollup[],
}

export type LichessRollup = {
  hostMod: LichessModule,
  input: string,
  output: string,
  importName?: string,
  plugins?: any[], // basically just to copy stuff in site bundle
  onWarn?: (w: any, wf: any) => any, // mostly to suppress 'this is undefined'
  isMain: boolean, // false for plugin bundles
};

const colorStr = (code: number, text: string) => cfg.color ? `\x1b[${code}m${text}\x1b[0m` : text;
export const red = (text: string): string => colorStr(31, text);
export const green = (text: string): string => colorStr(32, text);
export const yellow = (text: string): string => colorStr(33, text);
export const blue = (text: string): string => colorStr(34, text);
export const magenta = (text: string): string =>colorStr(35, text);
export const cyan = (text: string): string => colorStr(36, text);
export const grey = (text: string): string => colorStr(37, text);

export const bleepLog = (ctx: string, buf: Buffer|string, withTime = true) => {
  const text = typeof buf == 'string' ? buf : buf.toString('utf8');
  const now = new Date();
  const pad = (n: number) => n < 10 ? `0${n}` : `${n}`;
  const time = `[${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}] `;

  text.split(/[\n\r\f]/).filter(nonEmpty => nonEmpty).forEach(
    (line) => console.log(`[${ctx}] ${withTime?time:''}${line}`));
}

export const build = async () => {

  await fs.promises.rm(tsconfigDir, {recursive: true, force: true});
  await fs.promises.mkdir(tsconfigDir);

  modules = new Map((await parseModules(uiDir)).map(mod => [mod.name, mod]));
  
  filterMods.forEach(x => modules.delete(x));

  modules.forEach(mod => {
    const deplist: string[] = [];
    for (const dep in mod.pkg.dependencies)
      if (modules.has(dep)) deplist.push(dep);
  
    moduleDeps.set(mod.name, deplist); // todo, convert deplist to LichessModule[]  ?
    mod.rollup?.forEach(r => {
      if (r.output && ![mod.name, mod.moduleAlias].includes(r.output))
        moduleDeps.set(r.output, [mod.name,...deplist])
    });
  });
  await makeBleepConfig();
  //gulpWatch();
  typescriptWatch();
  deferred = rollupWatch.bind(this, [...modules.values()]);
  //deferred = rollupWatch.bind(this, depsFor('site'))
}

const postModBuild = (mod: LichessModule|undefined) => {
  mod?.build.post?.forEach((args:string[]) => {
    bleepLog(mod.name, args.join(' '));
    cps.exec(`${args.join(' ')}`, {cwd: mod.root}, (err, stdout, stderr) => {
      if (stdout) bleepLog(mod.name, stdout);
      if (stderr) bleepLog(mod.name, stderr);
      if (err) bleepLog(mod.name, `script error: ${err}`);
    });
  })
}

const preModBuild = (mod: LichessModule|undefined) => {
  mod?.build.pre?.forEach((args:string[]) => {
    bleepLog(mod.name, args.join(' '));
    const stdout = cps.execSync(`${args.join(' ')}`, {cwd:mod.root});
    // we need this to block, otherwise the rollup that may depend on this command
    // will begin executing
    if (stdout) bleepLog(mod.name, stdout);
  });
}

const typescriptWatch = () => {
  tsc = cps.spawn(
    'tsc', [
      '-b', 
      path.resolve(tsconfigDir,'bleep.tsconfig.json'),
      '--incremental',
      '-w',
      '--preserveWatchOutput'
    ]
  );
  tsc.stdout?.on('data', (txt: Buffer) => {
    if (!watcher && txt.toString().search("Found 0 errors.") >= 0) {
      // our rollup startup trigger depends on exact tsc output.
      // this is brittle but easily fixed if tsc output changes
      deferred(); 
    }
    else {
      bleepLog('tsc', txt);
    }
  });
  tsc.stderr?.on('data', txt => bleepLog('tsc', txt));
}

const gulpWatch = () => {
  gulp = cps.spawn('yarn', ['gulp', 'css'], {cwd: uiDir});
  gulp.stdout?.on('data', (txt: Buffer) => {
    bleepLog('gulp', txt, false);
  })
  gulp.stderr?.on('data', (txt: Buffer) => {
    bleepLog('gulp', txt, false);
  })
}

// we don't have to worry about order here, but we do need depended mods
const depClosure = (modName: string): LichessModule[] => {
  const depSet = new Set<LichessModule>();
  const collect = (modName: string, depSet: Set<LichessModule>, depth: number) => {
    if (depth > 8)
      throw `dependency sanity check failed - ${[...depSet.values()].map(m => m.name)}`;

    moduleDeps.get(modName)?.forEach(dep => collect( dep, depSet, depth + 1));
    depSet.add(modules.get(modName)!);
  }
  collect(modName,depSet,0);
  return [...depSet];
}

const rollupWatch = (todo: LichessModule[]) => {
  ps.chdir(uiDir);

  const outputToHostMod = new Map<string, LichessModule>();
  const triggerScriptCmds = new Set<string>();
  const rollups: rup.RollupOptions[] = [];
  
  todo.forEach(mod => {
    mod.rollup?.forEach(r => {
      const options = rollupOptions(r);
      const output = path.resolve(outDir, `${r.output}.js`);
      outputToHostMod.set(output, mod);
      if (r.isMain) triggerScriptCmds.add(output);
      rollups.push(options);
    });
  });
  watcher = rup.watch(rollups).on('event', (e: rup.RollupWatcherEvent) => {
    if (e.code == 'END') bleepLog('bleep','idle...');
  
    else if (e.code == 'BUNDLE_START') {
        const output = e.output.length > 0 ? e.output[0] : '';
        if (triggerScriptCmds.has(output))
          preModBuild(outputToHostMod.get(output));
    } else if (e.code == 'BUNDLE_END') {
        const output = e.output.length > 0 ? e.output[0] : '';
        const hostMod = outputToHostMod.get(output);
        if (triggerScriptCmds.has(output))
          postModBuild(hostMod);
        const modName = fs.existsSync(output) ? path.basename(output) : '<unknown>';
        const rss = `${Math.round(ps.memoryUsage.rss() / (1000 * 1000))} MB`;

        bleepLog('rollup', `bundled '${cyan(modName)}' - ` + grey(`${e.duration}ms [rss ${rss}]`));
        
        e.result?.close();
    } else if (e.code == 'ERROR') {
        bleepLog('rollup',red(`${e}`));
    }
  });
    //watcher.on('restart', () => { console.log('restart') })
}

const bundleDone = (output: string, result: rup.RollupBuild, dur: number) => {

}

const rollupOptions = (o: LichessRollup): rup.RollupWatchOptions => {
  const modDir = o.hostMod.root;
  const plugins = (o.plugins || [])
    .concat(o.hostMod.hasTsconfig ? [
      typescript({tsconfig: path.resolve(modDir, 'tsconfig.json')}),
      resolve(),
      commonjs({extensions: ['.js']}),      
    ] : []);
  return {
    input: path.resolve(modDir, o.input),
    plugins: plugins,
    onwarn: o.onWarn,
    output: {
      format: 'iife',
      name: o.importName,
      file: path.resolve(outDir, `${o.output}.js`),
      generatedCode: { preset: 'es2015', constBindings: false },
    },
  }
}
