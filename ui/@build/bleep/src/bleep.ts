import resolve from '@rollup/plugin-node-resolve';
import commonjs from '@rollup/plugin-commonjs';
import typescript from '@rollup/plugin-typescript';
import * as rollup from 'rollup';
import * as fs from 'fs';
import * as path from 'path';
import * as ps from 'process';
import parseModules from './parse';

export const outDir = '/Users/gamblej/ws/lichess/lila-local/public/compiled';
export const uidir = '/Users/gamblej/ws/lichess/lila-local/ui'
export const bleepDir = '/Users/gamblej/ws/lichess/lila-local/ui/@build/bleep';
export let launchDeps:Map<string, string[]>;

export type Module = {
  name: string,
  rollupAlias?: string, // used to distinguish plugins
  root: string,
  pkg: any,
  deps?: string[],
  build?: string[][],
  tscOptions?: string[],
  rollup?: RollupSpec[],
}
export type RollupSpec = {
  name: string,
  mod: string,
  input: string,
  output?: string
}
const main = async () => {

  ps.chdir(uidir);//path.resolve(uidir,'@build','bleep'));

  fs.rmSync(path.resolve(bleepDir,'.tsconfig'),{recursive:true,force:true});
  fs.mkdirSync(path.resolve(bleepDir,'.tsconfig'));

  const modules = new Map((await parseModules(uidir)).map(mod => [mod.name, mod]));
  
  launchDeps = new Map<string, string[]>();
  modules.forEach(mod => {
    const deplist: string[] = [];
    for (const dep in mod.pkg.dependencies) {
      if (modules.has(dep)) deplist.push(dep);
    }
    launchDeps.set(mod.name, deplist);
  })
  //tscBuildsSync([...modules.values()].filter(m => m.tscOptions));
  //const rollupBuilds = Array.from(modules.values());//[...modules.values()].filter(m => m.rollup);
  // rollup handles its own dependencies, but during launch we have an in-flight
  // cap due to cpu & memory constriants, so graph the deps and sort
  const watches: rollup.RollupWatchOptions[] = [];
  for (const [name, mod] of modules) {

    if (mod.root == uidir) {
      console.log(mod);
    }

    makeTsConfig(mod);

    mod.rollup?.forEach((cfg:RollupSpec) => {
      if (![name, mod.rollupAlias].includes(cfg.output))
        launchDeps.set(cfg.output!, [name,...launchDeps.get(name)!])
      watches.push(rollupOptions(cfg));
    });
  }
  const rollupBuilds = [...modules.values()].sort(
    (lhs,rhs) => launchDeps.get(lhs.name)!.length - launchDeps.get(rhs.name)!.length
  );
  console.log(rollupBuilds);
  for (const [k,v] of launchDeps) {
    //console.log(`${k}: ${v}`);
  }
  for (const du in [...modules.values()].filter(m => m.tscOptions)) {
    //console.log('tsc: '+tscBuilds[du].name);
  }
  for (const ru in rollupBuilds) {
    //console.log('rollup: '+rollupBuilds[ru].name);
    //console.log('  deps:   '+deps.get(rollupBuilds[ru].name));
  }
  //rollupWatch;
  //rollupWatch(modspecs);
}

const makeTsConfig = (mod: Module) => {
  fs.copyFileSync(
    path.resolve(mod.root, 'tsconfig.json'),
    path.resolve(bleepDir, `.tsconfig/${mod.name}.tsconfig.json`)
  );
}
const rollupOptions = (spec: RollupSpec): rollup.RollupWatchOptions => {
  const bleepDir = path.resolve(process.cwd(),'@build','bleep');
  const modDir = path.resolve(process.cwd(), spec.mod);
  return {
    input: path.resolve(modDir, spec.input),
    plugins: [
      typescript({tsconfig: path.resolve(bleepDir, '.tsconfig', `${spec.mod}.tsconfig.json`)}),
      resolve(),
      commonjs({extensions: ['.js']})
    ],
    output: spec.output ? {
      format: 'iife',
      name: spec.name,
      file: path.resolve(process.cwd(), `../public/compiled/${spec.output}.js`),
      generatedCode: { preset: 'es2015', constBindings: false },
    } : undefined,
    /*watch: {
      //exclude: 'node_modules/**',
      clearScreen: false
    }*/
  }
}

/*watch: {
    buildDelay,
    chokidar,
    clearScreen,
    skipWrite,
    exclude,
    include
  }
};*/

const rollupWatch = (modarray:any) => {
  const watcher = rollup.watch(modarray);
  watcher.on('event', e => {
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
        console.log(e.output);
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