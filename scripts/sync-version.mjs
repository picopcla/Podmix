import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const packagePath = path.join(root, 'package.json')
const androidGradlePath = path.join(root, 'android', 'app', 'build.gradle')
const envPath = path.join(root, 'empty')

function readJSON(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function write(file, content) {
  fs.writeFileSync(file, content)
}

function parseVersion(version) {
  const match = /^([0-9]+)\.([0-9]+)\.([0-9]+)$/.exec(version)
  if (!match) throw new Error(`Unsupported version format: ${version}`)
  return { major: Number(match[1]), minor: Number(match[2]), patch: Number(match[3]) }
}

function formatVersion({ major, minor, patch }) {
  return `${major}.${minor}.${patch}`
}

function versionCodeFrom(version) {
  const { major, minor, patch } = parseVersion(version)
  return major * 10000 + minor * 100 + patch
}

function bump(version, part) {
  const next = { ...parseVersion(version) }
  if (part === 'major') {
    next.major += 1
    next.minor = 0
    next.patch = 0
  } else if (part === 'minor') {
    next.minor += 1
    next.patch = 0
  } else {
    next.patch += 1
  }
  return formatVersion(next)
}

const [, , command = 'sync', bumpPart = 'patch'] = process.argv
const pkg = readJSON(packagePath)
let version = pkg.version

if (command === 'bump') {
  version = bump(version, bumpPart)
  pkg.version = version
  write(packagePath, `${JSON.stringify(pkg, null, 2)}\n`)
}

const versionCode = versionCodeFrom(version)
const gradle = fs.readFileSync(androidGradlePath, 'utf8')
const updatedGradle = gradle
  .replace(/versionCode\s+\d+/, `versionCode ${versionCode}`)
  .replace(/versionName\s+"[^"]+"/, `versionName "${version}"`)
write(androidGradlePath, updatedGradle)

const envLines = fs.readFileSync(envPath, 'utf8').split(/\r?\n/)
const nextEnv = envLines.map((line) => line.startsWith('VITE_APP_VERSION ') ? `VITE_APP_VERSION ${version}` : line).join('\n')
write(envPath, `${nextEnv.endsWith('\n') ? nextEnv : `${nextEnv}\n`}`)

console.log(JSON.stringify({ command, version, versionCode }, null, 2))
