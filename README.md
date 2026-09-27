# オリジナルFASTAに対してBLAT・ローカルBLAST+を利用可能なIGV改変版

## ローカル BLAT 検索

Java・BLAST+ と一緒に、配列相同性検索用の **UCSC BLAT** も同梱しています。**View > Preferences > Advanced > BLAT URL (or local)** に `local` を入力して保存すると、既存の BLAT メニューから読み込んだ参照配列をローカルで検索できます。シェルスクリプトや Python、BLAT サーバーの起動は不要です。

`Local BLAT executable` の既定値は `auto` です。PATH 上の `blat` を優先し、見つからなければ `blat/<platform>/bin` の同梱版を使います。実行ファイルのフルパス、または `bin` ディレクトリを明示することもできます。空白を含むパスも使えます。`Local BLAT timeout` の既定値は 600 秒です。

ローカル FASTA を直接検索し、圧縮 FASTA やリモート参照の場合は読み込んだゲノム配列を一時 FASTA に書き出して検索します。BLAST 用 DB は使用しません。20〜8,000 塩基を受け付け、元の配列 ID と両鎖の座標を保持して既存の BLAT トラックへ表示します。BLAT URL に従来の URL や `/path/to/blat.sh $DB $SEQUENCE` 形式のコマンドを設定する方法も引き続き使えます。

Linux / macOS は x64・ARM64 それぞれの [UCSC 公式バイナリ](https://hgdownload.soe.ucsc.edu/admin/exe/)を同梱します。Windows は UCSC v503 のソースから Cygwin でビルドした x64 版と必要な DLL を同梱し、Cygwin の事前インストールは不要です。Windows ARM64 では BLAST+ と同様に Windows 11 の x64 エミュレーションで動作します。

BLAT は個人・学術・非営利用途のライセンスで配布され、商用利用には [別途 BLAT ライセンス](https://genome.ucsc.edu/license/)が必要です。各同梱フォルダに元のライセンスを含め、Windows 版には実行用ライブラリのライセンス・対応するソースアーカイブ・ビルド手順も含めます。

```sh
./gradlew downloadBlat                 # 現在の OS 用
./gradlew downloadAllBlat              # 全 OS / CPU 用
./gradlew createPortableDistributions  # Java・BLAST+・BLAT を含む6種類の ZIP
```

Unix バイナリ・UCSC ソースは SHA-256、Windows ビルド用 Cygwin パッケージは公式メタデータの SHA-512 を固定して検証し、`build/blat-downloads` にキャッシュします。Unix の配布 URL が更新されて固定ハッシュと一致しない場合はビルドを停止するため、更新内容を確認して `gradle/blat.gradle` のハッシュを変更してください。Windows 版の初回ビルドは Windows 上で実行してください。コンパイラーも自動取得し、管理者権限・WSL は不要です。別の OS で全配布を作成するときは、事前に作った Windows 同梱フォルダを `-PblatWindowsBundle=/path/to/windows-x64` で指定できます。

## ローカル BLAST+ 検索

BLAST+ の `blastn` を使い、独自の塩基配列データベースをローカルで検索できます。通常は手動でのツール設定・DB 作成は不要です。

1. 参照 FASTA を IGV のゲノムとして読み込みます。
2. **Tools > BLAST ...** または配列の右クリックメニューから検索します。DB がなければ `makeblastdb -dbtype nucl -parse_seqids` を自動実行します。

ツールは **PATH 上の `blastn` / `makeblastdb` を優先**し、見つからないツールは同梱版を使います。実行ファイルを明示した場合はその指定を優先し、`makeblastdb` はまず同じディレクトリから探します。同梱版は [NCBI 公式配布](https://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/LATEST/)の BLAST+ **2.17.0** です。

DB は IGV キャッシュ内の `blast` フォルダへ作成し、次回以降は再利用します。ローカル FASTA の場所・サイズ・更新日時が変わると新しい DB を作ります。参照の横に既存 DB がある場合はそれも利用します。圧縮 FASTA は自動で展開し、2bit・GenBank など FASTA パスのない参照は読み込んだゲノム配列から FASTA を書き出して作成します。参照ファイル自体は変更しません。

自動作成時は `ENA|…` などを NCBI の ID パーサーが誤解釈しないよう、DB 内だけ短い内部用 ID に置き換えます。`*.igv-ids.tsv` に対応を保存し、検索結果・トラック・TSV 出力・セッションには元の配列 ID を使用します。この DB を別の場所へ移す場合は対応ファイルも一緒にコピーしてください。ユーザーが指定した既存 DB の ID は変更しません。

検索方式などを変更する場合は **View > Preferences > Advanced > Local BLAST+** を設定します。

   | 設定 | 内容 |
   | --- | --- |
   | BLAST+ blastn executable | 既定値 `auto`：PATH → 同梱版。任意で実行ファイルのフルパス、または BLAST+ の `bin` ディレクトリを指定できます |
   | BLAST database prefix | 空欄：現在の参照から自動作成・再利用。既存 DB のプレフィックスも指定可能。指定先に DB がなければ参照から作成します。`.nin` や `.nsq` を付けません |
   | BLAST search task | `auto` は 50 塩基未満で `blastn-short`、それ以外で `blastn`。`megablast` なども選択可能 |
   | BLAST DUST low-complexity filtering | 既定値は無効（`-dust no`）。低複雑性・反復配列も検索に使います。チェックすると通常の DUST フィルタリング（`-dust yes`）を有効にします |
   | BLAST E-value threshold | E-value の上限（既定値：10） |
   | BLAST maximum target sequences | 保持する検索対象配列数（既定値：100）。各配列に複数の HSP がある場合、結果行数はこれを超えます |
   | BLAST threads | 使用スレッド数（既定値：1） |
   | BLAST timeout (seconds) | DB 作成・検索それぞれのタイムアウト（既定値：600 秒） |

   実行ファイル・データベースの設定欄には引用符やコマンド引数を加えず、パスだけを入力してください。空白を含むパスも使えます。

**Tools > BLAST ...** には配列または単一の FASTA レコードを入力できます。リード・クリップ配列・挿入配列・アノテーション・選択領域の右クリックメニューからも検索できます。7〜1,000,000 塩基を検索でき、BLAT の 8 kb 制限は適用されません。RNA の U は T に変換します。

[DUST](https://www.ncbi.nlm.nih.gov/books/NBK52640/) はクエリの低複雑性領域をマスクする機能です。この改変版では全 task で `-dust no` を明示し、そのような領域からもヒットを探します。DUST の設定変更で DB を作り直す必要はありません。

結果は HSP ごとのトラックと表に表示され、表には identity、E-value、bit score が表示されます。表の座標は 1-based・両端を含む表記です。行を選ぶと対応位置へ移動でき、**Save results...** で標準の BLAST tabular（12 列）形式を保存できます。トラックは各 HSP の検索対象上の範囲を表示します。

データベースの配列 ID は、IGV で開いた FASTA の配列 ID と対応させてください（`makeblastdb -parse_seqids` を推奨）。現在のゲノムに存在しない配列へのヒットも表に残りますが、そのヒットはトラック表示・位置移動の対象外です。

DB 作成中・検索中は Cancel で中止できます。同時に複数の検索を始めても同じ DB の作成は一度だけ実行します。セッションには結果自体を保存するため、再読込時に BLAST+ を実行し直す必要はありません。

### BLAST+・BLAT の同梱と各 OS の配布

`createDist` はホスト OS に合う BLAST+ を NCBI から取得して `blast/<platform>/bin` に、BLAT を `blat/<platform>/bin` に同梱します。BLAST+ は NCBI の MD5 チェックサムを確認し、ダウンロードは `build/blast-downloads` にキャッシュします。バイナリは Git 管理の対象に含めません。

| 配布ターゲット | 同梱版 |
| --- | --- |
| Windows | x64（Windows ARM64 では x64 エミュレーションを使用） |
| macOS | universal（Intel / Apple Silicon） |
| Linux | x64 / ARM64 |

```sh
./gradlew downloadBlast                # 現在の OS 用のみ取得
./gradlew downloadAllBlast             # 全 OS / CPU 用を取得
./gradlew createDist                   # 現在の OS 用の起動フォルダ
./gradlew createWinDist                # Windows 用
./gradlew createMacDistZip             # macOS universal 用 ZIP
./gradlew createLinuxDistZip           # Linux x64 用 ZIP
./gradlew -PblastLinuxPlatform=linux-arm64 createLinuxDistZip
```

`createMacAppDist` / Java 同梱版の各ターゲットにも対象 OS の BLAST+・BLAT を含めます。通常の macOS ZIP / app は BLAT の Intel・Apple Silicon 版を両方含めます。Unix 用 ZIP では `blastn` / `makeblastdb` / `blat` の実行権限を保持します。バージョン更新時は `-PblastVersion=バージョン番号`、通常の `createDist` の対象変更には `-PblastPlatform=windows-x64|macos-universal|linux-x64|linux-arm64` を使用できます。Windows でのビルドには `gradlew.bat` を使います。

Linux の NCBI バイナリは OS の共有ライブラリを使用します。最小構成の Debian / Ubuntu では `libgomp1`、`libbz2-1.0`、`zlib1g`、`libstdc++6` をインストールしてください。今回の 2.17.0 バイナリが要求する glibc は x64 が 2.28 以降、ARM64 が 2.34 以降です（ARM64 は GCC 11 相当の `libstdc++` も必要）。Ubuntu 22.04 以降は両方の条件を満たします。これらのシステムライブラリは IGV に同梱しません。

### Java・BLAST+・BLAT を含む起動ディレクトリ

`./gradlew createPortableDistributions` で、以下の6種類を `build/portable` に作成し、同じ内容の ZIP を `build/distributions` に出力します。Java は [Adoptium Temurin](https://adoptium.net/temurin/releases?version=21) のデスクトップ用 JRE **21.0.12.1+1** を使用し、公式 SHA-256 チェックサムを確認して取得します。Java / BLAST+ / BLAT の事前インストールは不要です。

| OS / CPU | ディレクトリ | 起動スクリプト |
| --- | --- | --- |
| Windows Intel / AMD 64bit（x86-64） | `IGV_windows-x64` | `Start-IGV.cmd` |
| Windows 11 ARM64 | `IGV_windows-arm64` | `Start-IGV.cmd` |
| Linux x64 | `IGV_linux-x64` | `Start-IGV.sh` |
| Linux ARM64 | `IGV_linux-arm64` | `Start-IGV.sh` |
| macOS Intel | `IGV_macos-x64` | `Start-IGV.command` |
| macOS Apple Silicon | `IGV_macos-arm64` | `Start-IGV.command` |

各ディレクトリには `lib`（IGV 本体）、`runtime`（Java）、`blast`（BLAST+）、`blat`（BLAT）を含みます。フォルダ全体を対象のマシンへコピーして使用してください。実行権限を保持して移すには `IGV_<OS-CPU>_WithJavaBlast.zip` を対象 OS で展開する方法を推奨します。Windows は `Start-IGV.cmd`、macOS は `.command` をダブルクリック、Linux は `./Start-IGV.sh` で起動できます。

Windows ARM64 版の Java は ARM64 ネイティブです。NCBI の [最新 Windows 配布](https://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/LATEST/)は x64 のため、BLAST+ は Windows x64 版を含め、[Windows 11 on Arm の x64 エミュレーション](https://learn.microsoft.com/en-us/windows/arm/apps-on-arm-x86-emulation)で実行します。Intel / AMD 向けも64bit版です。32bit（x86）の Windows は、この最新版同梱配布の対象外です。

Windows の両パッケージでは、BLAST+ が必要とする x64 の C++ ランタイム DLL を Temurin x64 版から `blast/windows-x64/bin` に同梱します。ARM64 の Java が使う DLL と混在せず、別途 C++ ランタイムをインストールせずに利用できます。

起動スクリプトは同梱 Java を直接呼び出し、起動場所やパス中の空白に依存しません。最大 Java ヒープは **64 GB（`-Xmx64g`）** です。BLAST+・BLAT は PATH を優先し、なければ同梱版を使います。メモリ設定などは既存の `$HOME/.igv/java_arguments` を利用できます。このファイルに有効な `-Xmx` 指定がある場合は、その値が優先されます。Linux の GUI 実行にはデスクトップ環境とその共有ライブラリも必要です。

最小構成の Debian / Ubuntu で GUI ライブラリが不足する場合は、例えば以下をインストールしてください（上記 BLAST+ の依存も含みます）。通常のデスクトップ版では多くが既に入っています。

```sh
sudo apt install libgomp1 libbz2-1.0 zlib1g libstdc++6 libx11-6 libxext6 libxrender1 libxtst6 libxi6 libfontconfig1 fonts-dejavu-core
```

個別のディレクトリだけを作成する場合は `createPortableWindowsX64`、`createPortableWindowsArm64`、`createPortableLinuxX64`、`createPortableLinuxArm64`、`createPortableMacosX64`、`createPortableMacosArm64`、個別 ZIP は末尾に `Zip` を付けたターゲットを使用できます。Java の版を変える場合は `-PjavaRuntimeVersion=21.0.12.1+1` の形式で指定します。

開発時の検証：

```sh
./gradlew test --tests 'org.broad.igv.blast.*'
# 実際の BLAST+ を使った統合テストも実行する場合：
./gradlew -Digv.blast.bin=/path/to/blast/bin test --tests 'org.broad.igv.blast.*'
```


![Build Status](https://github.com/igvteam/igv/actions/workflows/gradle.yml/badge.svg)
![GitHub issues](https://img.shields.io/github/issues/igvteam/igv)
![GitHub closed issues](https://img.shields.io/github/issues-closed/igvteam/igv)
![](https://img.shields.io/npm/l/igv.svg)

Integrative Genomics Viewer - desktop genome visualization tool for Mac, Windows, and Linux.

### Building

These instructions are meant for developers interested in working on the IGV code. For normal use,
we recommend the pre-built releases available
at [http://software.broadinstitute.org/software/igv/download](http://software.broadinstitute.org/software/igv/download).

Builds are executed from the IGV project directory. Files will be created in the 'build' subdirectory.

IGV requires **Java 21** to build and run. Later versions of Java should work but we build and test on **Java 21**.

NOTE: If on a Windows platform use ```./gradlew.bat``` in the instructions below

#### Folder structure and build targets

The IGV bundles ship with embedded JREs from AdoptOpenJDK.

* Install Gradle for your platform. See https://gradle.org/ for details.

* Use ```./gradlew createDist``` to build a distribution directory (found in ```build/IGV-dist```) containing
  the igv.jar and its required runtime third-party dependencies as well as helper scripts for launching.

    * Launch IGV with `igv.sh` or `igv_hidpi.sh` on Linux, `igv.command` on Mac, and `igv.bat` on Windows.

    * To run igvtools from the command line use the script `igvtools` on Linux and Mac, or igvtools.bat
      on Windows. See the instructions in igvtools_readme.txt in that directory.

    * The launcher scripts expect this folder structure in order to run IGV.

* Use ```./gradlew test``` to run the test suite. See 'src/test/README.txt' for more information about running
  the tests.

* See this [README](https://raw.githubusercontent.com/igvteam/igv/master/scripts/readme.txt) for tips about using the
  IGV launcher scripts.

* This dashboard describes [project structure and dependencies](https://sourcespy.com/github/igvteamigv/).

Note that Gradle creates a number of other subdirectories in 'build'. These can be safely ignored.

#### Amazon Web Services support

Public data files hosted in Amazon S3 buckets can be loaded into IGV
using [https endpoints](https://docs.aws.amazon.com/AmazonS3/latest/dev/UsingBucket.html).

Authenticated access using s3:// urls is supported by either (1) enabling OAuth access with Cognito using the UMCCR
contributed AWS configuration option, or (2) setting AWS credentials and region information as described
[here]( https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html) and
[here](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/java-dg-region-selection.html).

For more details on using Cognito for OAuth access, see
the [UMCCR documentation on the backend](https://umccr.org/blog/igv-amazon-backend-setup/)
and [frontend for a provisioning URL step by step guide](https://umccr.org/blog/igv-amazon-frontend-setup/).


 
