import * as rup from 'rollup';
import * as fs from 'fs';
import * as path from 'path';
import * as ps from 'process';
import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from '@rollup/plugin-typescript';
import { parseModules } from './parse';
import { makeBleepConfig } from './tsconfig';

export const rootDir = '/Users/gamblej/ws/lichess/lila-local';
export const outDir = path.resolve(rootDir,'public/compiled');
export const uidir = path.resolve(rootDir,'ui');
export const bleepDir = path.resolve(uidir,'@build/bleep');
export const tsconfigDir = path.resolve(bleepDir,'.tsconfig');
export const launchDeps = new Map<string, string[]>();
export let modules: Map<string, LilaModule>;

// rename LilaModule - lila is the server
export type LilaModule = {
  name: string,
  rollupAlias?: string, // used to distinguish plugins
  root: string,
  pkg: any,
  deps?: string[],
  build?: string[][],
  tscOptions?: string[],
  rollup?: LilaRollup[],
}

export type LilaRollup = {
  dirName: string,
  input: string,
  output: string,
  modName?: string,
  customTsc?: boolean
  hasTsConfig?: boolean
};

const rollupOptions = (o: LilaRollup): rup.RollupWatchOptions => {
  const modDir = path.resolve(uidir, o.dirName);
  return {
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
    watch: {
      //exclude: 'node_modules/**',
      clearScreen: false
    }
  }
}

const main = async () => {

  fs.rmSync(tsconfigDir,{recursive:true,force:true});
  fs.mkdirSync(tsconfigDir);

  modules = new Map((await parseModules(uidir)).map(mod => [mod.name, mod]));

  modules.forEach(mod => {
    const deplist: string[] = [];
    for (const dep in mod.pkg.dependencies)
      if (modules.has(dep)) deplist.push(dep);
  
    launchDeps.set(mod.name, deplist); // todo, convert deplist to LilaModule[]  ?
    mod.rollup?.forEach(r => {
      if (![mod.name, mod.rollupAlias].includes(r.output))
        launchDeps.set(r.output!, [mod.name,...deplist])
    });
  });

  makeBleepConfig();
  rollupWatch();
}

const slices: LilaRollup[][] = []

const rollupWatch = () => {
  const rollups = [...modules.values()].filter(m=>m.rollup).map(m=>m.rollup!).flat().sort(
    (lhs,rhs) => launchDeps.get(lhs!.dirName)!.length - launchDeps.get(rhs!.dirName)!.length
  );
  nextRollups(rollups);
}
const nextRollups = (rollups: LilaRollup[]): rup.RollupWatcher|null => {
  if (rollups.length == 0) return null;

  ps.chdir(path.resolve(uidir));
  
  const opts = rollups.map(x=>rollupOptions(x));
  return rup.watch(opts).on('event', e => {
    switch (e.code) {
      case 'END':
        console.log('Idle...');
        
        break;
      case 'START':
        break;
      case 'BUNDLE_START':
        break;
      case 'BUNDLE_END':
        console.log(`processed: ${e.output} ${ps.memoryUsage.rss()/(1000*1000)} MB'`);
        e.result?.close();
        break;
      case 'ERROR':
        console.log(e.error);
    }
  });
    //watcher.on('change', (id, { event }) => { console.log(`${id}, ${event}`)})
    //watcher.on('restart', () => { console.log('restart') })
    //watcher.on('close', () => { console.log('close')})
}

main()

/*
    mod.rollup?.forEach((cfg:RollupSpec) => {
      if (![name, mod.rollupAlias].includes(cfg.output))
        launchDeps.set(cfg.output!, [name,...launchDeps.get(name)!])
      mod.rollupOptions.push(rollupOptions(cfg));
    });
  }
  const rollupBuilds = watches.sort(
    (lhs,rhs) => launchDeps.get(lhs.mod)!.length - launchDeps.get(rhs.name)!.length
  );
  //console.log(rollupBuilds);
  for (const [k,v] of launchDeps) {
    //console.log(`${k}: ${v}`);
  }
  for (const du in [...modules.values()].filter(m => m.tscOptions)) {
    //console.log('tsc: '+tscBuilds[du].name);
  }
  for (const ru in rollupBuilds) {
    console.log('rollup: '+rollupBuilds[ru].name);
    console.log('  deps:   '+launchDeps.get(rollupBuilds[ru].name));
  }*/
  //rollupWatch;
  //rollupWatch(modspecs);


/*watch: {
    buildDelay,
    chokidar,
    clearScreen,
    skipWrite,
    exclude,
    include
  }
};*/

// to stop watching
//watcher.close();

    // event.code can be one of:
    //   START        — the watcher is (re)starting
    //   BUNDLE_START — building an individual bundle
    //                  * event.input will be the input options object if present
    //                  * event.output contains an array of the "file" or
    //                    "dir" option values of the generated outputs
    //   BUNDLE_END   — finished building a bundle
    //                  * event.input will be the input options object if present
    //                  * event.output contains an array of the "file" or
    //                    "dir" option values of the generated outputs
    //                  * event.duration is the build duration in milliseconds
    //                  * event.result contains the bundle object that can be
    //                    used to generate additional outputs by calling
    //                    bundle.generate or bundle.write. This is especially
    //                    important when the watch.skipWrite option is used.
    //                  You should call "event.result.close()" once you are done
    //                  generating outputs, or if you do not generate outputs.
    //                  This will allow plugins to clean up resources via the
    //                  "closeBundle" hook.
    //   END          — finished building all bundles
    //   ERROR        — encountered an error while bundling
    //                  * event.error contains the error that was thrown
    //                  * event.result is null for build errors and contains the
    //                    bundle object for output generation errors. As with
    //                    "BUNDLE_END", you should call "event.result.close()" if
    //                    present once you are done.
    // If you return a Promise from your event handler, Rollup will wait until the
    // Promise is resolved before continuing.


/*

import { loadConfigFile } from 'rollup';
const path = require('node:path');
const rollup = require('rollup');

// load the config file next to the current script;
// the provided config object has the same effect as passing "--format es"
// on the command line and will override the format of all outputs
loadConfigFile(path.resolve(__dirname, 'rollup.config.js'), { format: 'es' }).then(
  async ({ options, warnings }) => {
    // "warnings" wraps the default `onwarn` handler passed by the CLI.
    // This prints all warnings up to this point:
    console.log(`We currently have ${warnings.count} warnings`);

    // This prints all deferred warnings
    warnings.flush();

    // options is an array of "inputOptions" objects with an additional "output"
    // property that contains an array of "outputOptions".
    // The following will generate all outputs for all inputs, and write them to disk the same
    // way the CLI does it:
    for (const optionsObj of options) {
      const bundle = await rollup.rollup(optionsObj);
      await Promise.all(optionsObj.output.map(bundle.write));
    }

    // You can also pass this directly to "rollup.watch"
    rollup.watch(options);
  }
);

*/