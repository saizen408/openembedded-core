SUMMARY = "Perl scripting language"
HOMEPAGE = "http://www.perl.org/"
SECTION = "devel"
LICENSE = "Artistic-1.0 | GPL-1.0+"
LIC_FILES_CHKSUM = "file://Copying;md5=5b122a36d0f6dc55279a0ebc69f3c60b \
                    file://Artistic;md5=71a4d5d9acc18c0952a6df2218bb68da \
                    "


SRC_URI = "https://www.cpan.org/src/5.0/perl-${PV}.tar.gz;name=perl \
           https://github.com/arsv/perl-cross/releases/download/1.3.4/perl-cross-1.3.4.tar.gz;name=perl-cross \
           file://perl-rdepends.txt \
           file://0001-configure_tool.sh-do-not-quote-the-argument-to-comma.patch \
           file://0001-ExtUtils-MakeMaker-add-LDFLAGS-when-linking-binary-m.patch \
           file://0001-Somehow-this-module-breaks-through-the-perl-wrapper-.patch \
           file://errno_ver.diff \
           file://native-perlinc.patch \
           file://0001-perl-cross-add-LDFLAGS-when-linking-libperl.patch \
           file://perl-dynloader.patch \
           file://0001-configure_path.sh-do-not-hardcode-prefix-lib-as-libr.patch \
           file://0002-Constant-Fix-up-shebang.patch \
           file://determinism.patch  \
           file://perl-cross-makefile.patch \
           "
SRC_URI_append_class-native = " \
           file://perl-configpm-switch.patch \
"
SRC_URI_append_class-target = " \
           file://encodefix.patch \
"

SRC_URI[perl.sha256sum] = "efeb1ce1f10824190ad1cadbcccf6fdb8a5d37007d0100d2d9ae5f2b5900c0b4"
SRC_URI[perl-cross.sha256sum] = "755aa0ca8141a942188a269564f86c3c82349f82c346ed5c992495d7f35138ba"

S = "${WORKDIR}/perl-${PV}"

inherit upstream-version-is-even update-alternatives

DEPENDS += "zlib virtual/crypt"

PERL_LIB_VER = "${@'.'.join(d.getVar('PV').split('.')[0:2])}.0"

PACKAGECONFIG ??= "bdb gdbm"
PACKAGECONFIG[bdb] = ",-Ui_db,db"
PACKAGECONFIG[gdbm] = ",-Ui_gdbm,gdbm"

# Don't generate comments in enc2xs output files. They are not reproducible
export ENC2XS_NO_COMMENTS = "1"

do_unpack_append() {
    bb.build.exec_func('do_copy_perlcross', d)
}

do_copy_perlcross() {
    cp -rfp ${WORKDIR}/perl-cross*/* ${S}
}

do_configure_class-target() {
    ./configure --prefix=${prefix} --libdir=${libdir} \
    --target=${TARGET_SYS} \
    -Duseshrplib \
    -Dsoname=libperl.so.5 \
    -Dvendorprefix=${prefix} \
    -Darchlibexp=${STAGING_LIBDIR}/perl5/${PV}/${TARGET_ARCH}-linux \
    ${PACKAGECONFIG_CONFARGS}

    #perl.c uses an ARCHLIB_EXP define to generate compile-time code that
    #adds the archlibexp path to @INC during run-time initialization of a
    #new perl interpreter.

    #Because we've changed this value in a temporary way to make it
    #possible to use ExtUtils::Embed in the target build (the temporary
    #value in config.sh gets re-stripped out during packaging), the
    #ARCHLIB_EXP value that gets generated still uses the temporary version
    #instead of the original expected version (i.e. becauses it's in the
    #generated config.h, it doesn't get stripped out during packaging like
    #the others in config.sh).

    sed -i -e "s,${STAGING_LIBDIR},${libdir},g" config.h
}

do_configure_class-nativesdk() {
    ./configure --prefix=${prefix} \
    --target=${TARGET_SYS} \
    -Duseshrplib \
    -Dsoname=libperl.so.5 \
    -Dvendorprefix=${prefix} \
    -Darchlibexp=${STAGING_LIBDIR}/perl5/${PV}/${TARGET_ARCH}-linux \
    ${PACKAGECONFIG_CONFARGS}

    # See the comment above
    sed -i -e "s,${STAGING_LIBDIR},${libdir},g" config.h
}

do_configure_class-native() {
    ./configure --prefix=${prefix} \
    -Dbin=${bindir}/perl-native \
    -Duseshrplib \
    -Dsoname=libperl.so.5 \
    -Dvendorprefix=${prefix} \
    -Ui_xlocale \
    ${PACKAGECONFIG_CONFARGS}
}

do_configure_append() {
    if [ -n "$SOURCE_DATE_EPOCH" ]; then
        PERL_BUILD_DATE="$(${PYTHON} -c "\
from datetime import datetime, timezone; \
print(datetime.fromtimestamp($SOURCE_DATE_EPOCH, timezone.utc).strftime('%a %b %d %H:%M:%S %Y')) \
            ")"
        echo "#define PERL_BUILD_DATE \"$PERL_BUILD_DATE\"" >> config.h
    fi
}

do_compile() {
    oe_runmake
    # This isn't generated reliably so delete and re-generate.
    # https://github.com/arsv/perl-cross/issues/86

    if [ -e pod/perltoc.pod ]; then
        bbnote Rebuilding perltoc.pod
        rm -f pod/perltoc.pod
        oe_runmake pod/perltoc.pod
    fi
}

do_install() {
    oe_runmake 'DESTDIR=${D}' install

    install -d ${D}${libdir}/perl5
    install -d ${D}${libdir}/perl5/${PV}/
    install -d ${D}${libdir}/perl5/${PV}/ExtUtils/

    # Save native config
    install config.sh ${D}${libdir}/perl5
    install lib/Config.pm ${D}${libdir}/perl5/${PV}/
    install lib/ExtUtils/typemap ${D}${libdir}/perl5/${PV}/ExtUtils/

    # Fix up shared library
    rm ${D}/${libdir}/perl5/${PV}/*/CORE/libperl.so
    ln -sf ../../../../libperl.so.${PERL_LIB_VER} $(echo ${D}/${libdir}/perl5/${PV}/*/CORE)/libperl.so
}

do_install_append_class-target() {
    # This is used to substitute target configuration when running native perl via perl-configpm-switch.patch
    ln -s Config_heavy.pl ${D}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/Config_heavy-target.pl

    # This contains host-specific information used for building miniperl (a helper executable built with host compiler)
    # and therefore isn't reproducible. I believe the file isn't actually needed on target.
    rm ${D}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/CORE/xconfig.h
}

do_install_append_class-nativesdk() {
    # This is used to substitute target configuration when running native perl via perl-configpm-switch.patch
    ln -s Config_heavy.pl ${D}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/Config_heavy-target.pl

    create_wrapper ${D}${bindir}/perl \
        PERL5LIB='$PERL5LIB:${SDKPATHNATIVE}/${libdir_nativesdk}/perl5/site_perl/${PV}:${SDKPATHNATIVE}/${libdir_nativesdk}/perl5/vendor_perl/${PV}:${SDKPATHNATIVE}/${libdir_nativesdk}/perl5/${PV}'
}

do_install_append_class-native () {
    # Those wrappers mean that perl installed from sstate (which may change
    # path location) works and that in the nativesdk case, the SDK can be
    # installed to a different location from the one it was built for.
    create_wrapper ${D}${bindir}/perl-native/perl PERL5LIB='$PERL5LIB:${STAGING_LIBDIR}/perl5/site_perl/${PV}:${STAGING_LIBDIR}/perl5/vendor_perl/${PV}:${STAGING_LIBDIR}/perl5/${PV}'

    # Use /usr/bin/env nativeperl for the perl script.
    for f in `grep -Il '#! *${bindir}/perl' ${D}/${bindir}/*`; do
            sed -i -e 's|${bindir}/perl|/usr/bin/env nativeperl|' $f
    done
}

PACKAGE_PREPROCESS_FUNCS += "perl_package_preprocess"

perl_package_preprocess () {
        # Fix up installed configuration
        sed -i -e "s,${D},,g" \
               -e "s,${DEBUG_PREFIX_MAP},,g" \
               -e "s,--sysroot=${STAGING_DIR_HOST},,g" \
               -e "s,-isystem${STAGING_INCDIR} ,,g" \
               -e "s,${STAGING_LIBDIR},${libdir},g" \
               -e "s,${STAGING_BINDIR},${bindir},g" \
               -e "s,${STAGING_INCDIR},${includedir},g" \
               -e "s,${STAGING_BINDIR_NATIVE}/perl-native/,${bindir}/,g" \
               -e "s,${STAGING_BINDIR_NATIVE}/,,g" \
               -e "s,${STAGING_BINDIR_TOOLCHAIN}/${TARGET_PREFIX},${bindir},g" \
               -e 's:${RECIPE_SYSROOT}::g' \
            ${PKGD}${bindir}/h2xs.perl \
            ${PKGD}${bindir}/h2ph.perl \
            ${PKGD}${bindir}/pod2man.perl \
            ${PKGD}${bindir}/pod2text.perl \
            ${PKGD}${bindir}/pod2usage.perl \
            ${PKGD}${bindir}/podchecker.perl \
            ${PKGD}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/CORE/config.h \
            ${PKGD}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/CORE/perl.h \
            ${PKGD}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/CORE/pp.h \
            ${PKGD}${libdir}/perl5/${PV}/Config.pm \
            ${PKGD}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/Config.pm \
            ${PKGD}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/Config.pod \
            ${PKGD}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/Config_git.pl \
            ${PKGD}${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/Config_heavy.pl \
            ${PKGD}${libdir}/perl5/${PV}/ExtUtils/Liblist/Kid.pm \
            ${PKGD}${libdir}/perl5/${PV}/FileCache.pm \
            ${PKGD}${libdir}/perl5/${PV}/pod/*.pod \
            ${PKGD}${libdir}/perl5/config.sh
}

inherit update-alternatives

ALTERNATIVE_PRIORITY = "100"

ALTERNATIVE_${PN}-misc = "corelist cpan enc2xs encguess h2ph h2xs instmodsh json_pp libnetcfg \
                     piconv pl2pm pod2html pod2man pod2text pod2usage podchecker \
                     prove ptar ptardiff ptargrep shasum splain xsubpp zipdetails"
ALTERNATIVE_LINK_NAME[corelist] = "${bindir}/corelist"
ALTERNATIVE_LINK_NAME[cpan] = "${bindir}/cpan"
ALTERNATIVE_LINK_NAME[enc2xs] = "${bindir}/enc2xs"
ALTERNATIVE_LINK_NAME[encguess] = "${bindir}/encguess"
ALTERNATIVE_LINK_NAME[h2ph] = "${bindir}/h2ph"
ALTERNATIVE_LINK_NAME[h2xs] = "${bindir}/h2xs"
ALTERNATIVE_LINK_NAME[instmodsh] = "${bindir}/instmodsh"
ALTERNATIVE_LINK_NAME[json_pp] = "${bindir}/json_pp"
ALTERNATIVE_LINK_NAME[libnetcfg] = "${bindir}/libnetcfg"
ALTERNATIVE_LINK_NAME[piconv] = "${bindir}/piconv"
ALTERNATIVE_LINK_NAME[pl2pm] = "${bindir}/pl2pm"
ALTERNATIVE_LINK_NAME[pod2html] = "${bindir}/pod2html"
ALTERNATIVE_LINK_NAME[pod2man] = "${bindir}/pod2man"
ALTERNATIVE_LINK_NAME[pod2text] = "${bindir}/pod2text"
ALTERNATIVE_LINK_NAME[pod2usage] = "${bindir}/pod2usage"
ALTERNATIVE_LINK_NAME[podchecker] = "${bindir}/podchecker"
ALTERNATIVE_LINK_NAME[prove] = "${bindir}/prove"
ALTERNATIVE_LINK_NAME[ptar] = "${bindir}/ptar"
ALTERNATIVE_LINK_NAME[ptardiff] = "${bindir}/ptardiff"
ALTERNATIVE_LINK_NAME[ptargrep] = "${bindir}/ptargrep"
ALTERNATIVE_LINK_NAME[shasum] = "${bindir}/shasum"
ALTERNATIVE_LINK_NAME[splain] = "${bindir}/splain"
ALTERNATIVE_LINK_NAME[xsubpp] = "${bindir}/xsubpp"
ALTERNATIVE_LINK_NAME[zipdetails] = "${bindir}/zipdetails"

require perl-ptest.inc

FILES_${PN} = "${bindir}/perl ${bindir}/perl.real ${bindir}/perl${PV} ${libdir}/libperl.so* \
               ${libdir}/perl5/site_perl \
               ${libdir}/perl5/${PV}/Config.pm \
               ${libdir}/perl5/${PV}/${TARGET_ARCH}-linux/Config.pm \
               ${libdir}/perl5/${PV}/*/Config_git.pl \
               ${libdir}/perl5/${PV}/*/Config_heavy-target.pl \
               ${libdir}/perl5/config.sh \
               ${libdir}/perl5/${PV}/strict.pm \
               ${libdir}/perl5/${PV}/warnings.pm \
               ${libdir}/perl5/${PV}/warnings \
               ${libdir}/perl5/${PV}/vars.pm \
               ${libdir}/perl5/site_perl \
               ${libdir}/perl5/${PV}/ExtUtils/MANIFEST.SKIP \
               ${libdir}/perl5/${PV}/ExtUtils/xsubpp \
               ${libdir}/perl5/${PV}/ExtUtils/typemap \
               "
RPROVIDES_${PN} += "perl-module-strict perl-module-vars perl-module-config perl-module-warnings \
                    perl-module-warnings-register"

FILES_${PN}-staticdev_append = " ${libdir}/perl5/${PV}/*/CORE/libperl.a"

FILES_${PN}-dev_append = " ${libdir}/perl5/${PV}/*/CORE"

FILES_${PN}-doc_append = " ${libdir}/perl5/${PV}/Unicode/Collate/*.txt \
                           ${libdir}/perl5/${PV}/*/.packlist \
                           ${libdir}/perl5/${PV}/Encode/encode.h \
                         "
PACKAGES += "${PN}-misc"

FILES_${PN}-misc = "${bindir}/*"

PACKAGES += "${PN}-pod"

FILES_${PN}-pod = "${libdir}/perl5/${PV}/pod \
                   ${libdir}/perl5/${PV}/*.pod \
                   ${libdir}/perl5/${PV}/*/*.pod \
                   ${libdir}/perl5/${PV}/*/*/*.pod \ 
                   ${libdir}/perl5/${PV}/*/*/*/*.pod \ 
                  "

PACKAGES += "${PN}-module-cpan ${PN}-module-unicore"

FILES_${PN}-module-cpan += "${libdir}/perl5/${PV}/CPAN \
                          "
FILES_${PN}-module-unicore += "${libdir}/perl5/${PV}/unicore"

ALTERNATIVE_PRIORITY = "40"
ALTERNATIVE_${PN}-doc = "Thread.3"
ALTERNATIVE_LINK_NAME[Thread.3] = "${mandir}/man3/Thread.3"

# Create a perl-modules package recommending all the other perl
# packages (actually the non modules packages and not created too)
ALLOW_EMPTY_${PN}-modules = "1"
PACKAGES += "${PN}-modules "

PACKAGESPLITFUNCS_prepend = "split_perl_packages "

python split_perl_packages () {
    libdir = d.expand('${libdir}/perl5/${PV}')
    do_split_packages(d, libdir, r'.*/auto/([^.]*)/[^/]*\.(so|ld|ix|al)', '${PN}-module-%s', 'perl module %s', recursive=True, match_path=True, prepend=False)
    do_split_packages(d, libdir, r'.*linux/([^\/]*)\.pm', '${PN}-module-%s', 'perl module %s', recursive=True, allow_dirs=False, match_path=True, prepend=False)
    do_split_packages(d, libdir, r'Module/([^\/]*)\.pm', '${PN}-module-%s', 'perl module %s', recursive=True, allow_dirs=False, match_path=True, prepend=False)
    do_split_packages(d, libdir, r'Module/([^\/]*)/.*', '${PN}-module-%s', 'perl module %s', recursive=True, allow_dirs=False, match_path=True, prepend=False)
    do_split_packages(d, libdir, r'.*linux/([^\/].*)\.(pm|pl|e2x)', '${PN}-module-%s', 'perl module %s', recursive=True, allow_dirs=False, match_path=True, prepend=False)
    do_split_packages(d, libdir, r'(^(?!(CPAN\/|CPANPLUS\/|Module\/|unicore\/|.*linux\/)[^\/]).*)\.(pm|pl|e2x)', '${PN}-module-%s', 'perl module %s', recursive=True, allow_dirs=False, match_path=True, prepend=False)

    # perl-modules should recommend every perl module, and only the
    # modules. Don't attempt to use the result of do_split_packages() as some
    # modules are manually split (eg. perl-module-unicore).
    packages = filter(lambda p: 'perl-module-' in p, d.getVar('PACKAGES').split())
    d.setVar(d.expand("RRECOMMENDS_${PN}-modules"), ' '.join(packages))

    # Read the pre-generated dependency file, and use it to set module dependecies
    for line in open(d.expand("${WORKDIR}") + '/perl-rdepends.txt').readlines():
        splitline = line.split()
        if bb.data.inherits_class('native', d):
            module = splitline[0] + '-native'
            depends = "perl-native"
        else:
            module = splitline[0].replace("RDEPENDS_perl", "RDEPENDS_${PN}")
            depends = splitline[2].strip('"').replace("perl-module", "${PN}-module")
        d.appendVar(d.expand(module), " " + depends)
}

python() {
    if d.getVar('CLASSOVERRIDE') == "class-target":
        d.setVar("PACKAGES_DYNAMIC", "^${MLPREFIX}perl-module-.*(?<!native)$")
    elif d.getVar('CLASSOVERRIDE') == "class-native":
        d.setVar("PACKAGES_DYNAMIC", "^perl-module-.*-native$")
    elif d.getVar('CLASSOVERRIDE') == "class-nativesdk":
        d.setVar("PACKAGES_DYNAMIC", "^nativesdk-perl-module-.*")
}

RDEPENDS_${PN}-misc += "perl perl-modules"
RDEPENDS_${PN}-pod += "perl"

BBCLASSEXTEND = "native nativesdk"

SSTATE_SCAN_FILES += "*.pm *.pod *.h *.pl *.sh"

do_create_rdepends_inc() {
    cd ${WORKDIR}
    cat <<'EOPREAMBLE' > ${WORKDIR}/perl-rdepends.inc

# Some additional dependencies that the above doesn't manage to figure out
RDEPENDS_${PN}-module-file-spec += "${PN}-module-file-spec-unix"
RDEPENDS_${PN}-module-math-bigint += "${PN}-module-math-bigint-calc"
RDEPENDS_${PN}-module-thread-queue += "${PN}-module-attributes"
RDEPENDS_${PN}-module-overload += "${PN}-module-overloading"

# Generated depends list beyond this line
EOPREAMBLE
    test -e packages-split.new && rm -rf packages-split.new
    cp -r packages-split packages-split.new && cd packages-split.new
    find . -name \*.pm | xargs sed -i '/^=head/,/^=cut/d'
    egrep -r "^\s*(\<use .*|\<require .*);?" perl-module-* --include="*.pm" | \
    sed "s/\/.*\.pm: */ += /g;s/[\"\']//g;s/;.*/\"/g;s/+= .*\(require\|use\)\> */+= \"perl-module-/g;s/CPANPLUS::.*/cpanplus/g;s/CPAN::.*/cpan/g;s/::/-/g;s/ [^+\"].*//g;s/_/-/g;s/\.pl\"$/\"/;s/\"\?\$/\"/;s/(//;" | tr [:upper:] [:lower:] | \
    awk '{if ($3 != "\x22"$1"\x22"){ print $0}}'| \
    grep -v -e "\-vms\-" -e module-5 -e "^$" -e "\\$" -e your -e tk -e autoperl -e html -e http -e parse-cpan -e perl-ostype -e ndbm-file -e module-mac -e fcgi -e lwp -e dbd -e dbix | \
    sort -u | \
    sed 's/^/RDEPENDS_/;s/perl-module-/${PN}-module-/g;s/module-\(module-\)/\1/g;s/\(module-load\)-conditional/\1/g;s/encode-configlocal/&-pm/;' | \
    egrep -wv '=>|module-a|module-apache.?|module-apr|module-authen-sasl|module-b-asmdata|module-convert-ebcdic|module-devel-size|module-digest-perl-md5|module-dumpvalue|module-extutils-constant-aaargh56hash|module-extutils-xssymset|module-file-bsdglob|module-for|module-it|module-io-socket-inet6|module-io-socket-ssl|module-io-string|module-ipc-system-simple|module-lexical|module-local-lib|metadata|module-modperl-util|module-pluggable-object|module-test-builder-io-scalar|module-test2|module-text-unidecode|module-unicore|module-win32|objects\sload|syscall.ph|systeminfo.ph|%s' | \
    egrep -wv '=>|module-algorithm-diff|module-carp|module-c<extutils-mm-unix>|module-encode-hanextra|module-extutils-makemaker-version-regex|module-file-spec|module-io-compress-lzma|module-locale-maketext-lexicon|module-log-agent|module-meta-notation|module-net-localcfg|module-net-ping-external|module-b-deparse|module-scalar-util|module-some-module|module-symbol|module-uri|module-win32api-file' >> ${WORKDIR}/perl-rdepends.generated
    cp ${WORKDIR}/perl-rdepends.generated ${THISDIR}/files/perl-rdepends.txt
}

# bitbake perl -c create_rdepends_inc
addtask do_create_rdepends_inc

SYSROOT_PREPROCESS_FUNCS += "perl_sysroot_create_wrapper"

perl_sysroot_create_wrapper () {
       mkdir -p ${SYSROOT_DESTDIR}${bindir}
       # Create a wrapper that /usr/bin/env perl will use to get perl-native.
       # This MUST live in the normal bindir.
       cat > ${SYSROOT_DESTDIR}${bindir}/nativeperl << EOF
#!/bin/sh
realpath=\`readlink -fn \$0\`
exec \`dirname \$realpath\`/perl-native/perl "\$@"
EOF
       chmod 0755 ${SYSROOT_DESTDIR}${bindir}/nativeperl
       cat ${SYSROOT_DESTDIR}${bindir}/nativeperl
}
